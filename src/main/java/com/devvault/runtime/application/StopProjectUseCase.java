package com.devvault.runtime.application;

import com.devvault.discovery.application.ProjectLookupService;
import com.devvault.runtime.domain.RuntimeInstance;
import com.devvault.runtime.infrastructure.DockerComposeRunner;
import com.devvault.runtime.infrastructure.RuntimeInstanceRepository;
import com.devvault.shared.api.exception.ApiException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.devvault.runtime.domain.Container;
import com.devvault.runtime.domain.ContainerKind;
import com.devvault.runtime.domain.Service;
import com.devvault.runtime.domain.ServiceStatus;
import com.devvault.runtime.infrastructure.ContainerRepository;
import com.devvault.runtime.infrastructure.ServiceRepository;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import java.util.UUID;

@Component
public class StopProjectUseCase {

    private static final Logger log = LoggerFactory.getLogger(StartProjectUseCase.class);

    private final ProjectLookupService projectLookupService;
    private final DockerComposeRunner composeRunner;
    private final RuntimeInstanceRepository runtimeInstanceRepository;
    private final ServiceRepository serviceRepository;
    private final ContainerRepository containerRepository;
    private final LocalProcessManager localProcessManager;

    public StopProjectUseCase(ProjectLookupService projectLookupService,
            DockerComposeRunner composeRunner,
            RuntimeInstanceRepository runtimeInstanceRepository,
            ServiceRepository serviceRepository,
            ContainerRepository containerRepository,
            LocalProcessManager localProcessManager
        ) {
        this.projectLookupService = projectLookupService;
        this.composeRunner = composeRunner;
        this.runtimeInstanceRepository = runtimeInstanceRepository;
        this.serviceRepository = serviceRepository;
        this.containerRepository = containerRepository;
        this.localProcessManager = localProcessManager;
    }

    /**
     * Detiene un proyecto.
     * @param projectId ID del proyecto
     * @return Instancia del runtime
     */
    public RuntimeInstance execute(UUID projectId) {
    // Obtenemos el proyecto
    ProjectLookupService.ProjectSummary project = projectLookupService.findById(projectId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Proyecto no encontrado: " + projectId));
 
    // Obtenemos la instancia del runtime
    RuntimeInstance instance = runtimeInstanceRepository.findFirstByProjectIdOrderByStartedAtDesc(projectId)
            .filter(RuntimeInstance::isActive)
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                    "El proyecto no tiene una instancia activa para detener"));
 
    // Obtenemos los servicios
    List<Service> services = serviceRepository.findByProjectId(projectId);
 
    boolean hasLocalProcess = false;
 
    // Iteramos sobre los servicios
    for (Service service : services) {
        // Obtenemos el contenedor
        Optional<Container> containerOpt = containerRepository.findByServiceId(service.getId());
        if (containerOpt.isEmpty()) continue;
 
        // Obtenemos el contenedor
        Container container = containerOpt.get();
 
        // Si el contenedor es un proceso local
        if (container.getKind() == ContainerKind.LOCAL_PROCESS) {
            hasLocalProcess = true;

            boolean killed = localProcessManager.stop(projectId);

            log.info(
                    ">>> Proceso local del proyecto {} detenido: {}",
                    projectId,
                    killed
            );

            // Actualizamos el estado del contenedor
            container.updateState("stopped");
            // Guardamos el contenedor
            containerRepository.save(container);

            // Actualizamos el estado del servicio
            service.updateStatus(ServiceStatus.STOPPED);
            // Guardamos el servicio
            serviceRepository.save(service);
        }
    }
 
    // Si no hay procesos locales
    if (!hasLocalProcess) {
        // Ningún servicio local — asumimos que es Docker Compose, comportamiento original
        Path composeFile = Path.of(project.path()).resolve("docker-compose.yml");
        String composeProjectName = "devvault-" + projectId.toString().replace("-", "");
        composeRunner.down(composeFile, composeProjectName);

    // Docker Compose detenido correctamente.
    // Actualizamos el estado persistido en DevVault.
    for (Service service : services) {

        Optional<Container> containerOpt =
                containerRepository.findByServiceId(service.getId());

        if (containerOpt.isPresent()) {

            Container container = containerOpt.get();

            container.updateState("stopped");
            containerRepository.save(container);
        }

        service.updateStatus(ServiceStatus.STOPPED);
        serviceRepository.save(service);
    }
    }
 
    instance.markStopped();
    return runtimeInstanceRepository.save(instance);
}

    /**
     * Stops any recorded local process or DevVault-managed Compose stack before
     * the project and its database records are deleted.
     */
    public void stopForDeletion(UUID projectId) {
        ProjectLookupService.ProjectSummary project = projectLookupService.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Proyecto no encontrado: " + projectId));
        RuntimeInstance latestInstance = runtimeInstanceRepository
                .findFirstByProjectIdOrderByStartedAtDesc(projectId)
                .orElse(null);
        Instant expectedStartedAt = latestInstance == null ? null : latestInstance.getStartedAt();
        boolean runtimeActive = latestInstance != null && latestInstance.isActive();
        List<Service> services = serviceRepository.findByProjectId(projectId);
        Path composeFile = Path.of(project.path()).resolve("docker-compose.yml");
        boolean dockerRuntimeNeedsStop = false;

        for (Service service : services) {
            Optional<Container> containerOpt = containerRepository.findByServiceId(service.getId());
            if (containerOpt.isEmpty()) {
                if (service.getStatus() != ServiceStatus.STOPPED) {
                    if (Files.exists(composeFile)) {
                        dockerRuntimeNeedsStop = true;
                    } else {
                        Process managed = localProcessManager.get(projectId);
                        if (managed == null || !managed.isAlive()) {
                            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                                    "Hay un servicio activo sin información de proceso; el proyecto no fue eliminado");
                        }
                    }
                }
                continue;
            }
            Container container = containerOpt.get();
            if (container.getKind() == ContainerKind.LOCAL_PROCESS) {
                Integer pid = container.getPid();
                boolean pidAlive = pid != null && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
                boolean managedAlive = localProcessManager.get(projectId) != null
                        && localProcessManager.get(projectId).isAlive();
                if (pidAlive || managedAlive) {
                    boolean stopped = localProcessManager.stop(projectId, pid, expectedStartedAt);
                    if (!stopped || (pid != null && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))) {
                        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                                "No se pudo detener el proceso local PID " + pid + "; el proyecto no fue eliminado");
                    }
                    Process managedProcess = localProcessManager.get(projectId);
                    if (managedProcess != null && managedProcess.isAlive()) {
                        throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                                "No se pudo detener el proceso local; el proyecto no fue eliminado");
                    }
                }
                container.updateState("stopped");
                containerRepository.save(container);
                service.updateStatus(ServiceStatus.STOPPED);
                serviceRepository.save(service);
            } else {
                boolean recordedAsActive = runtimeActive
                        || service.getStatus() != ServiceStatus.STOPPED
                        || !"stopped".equalsIgnoreCase(container.getState());
                dockerRuntimeNeedsStop |= recordedAsActive;
            }
        }

        Process managedProcess = localProcessManager.get(projectId);
        if (managedProcess != null && managedProcess.isAlive()) {
            localProcessManager.stop(projectId);
            if (managedProcess.isAlive()) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "No se pudo detener el proceso local; el proyecto no fue eliminado");
            }
        }

        if (runtimeActive && Files.exists(composeFile)) {
            dockerRuntimeNeedsStop = true;
        }
        if (dockerRuntimeNeedsStop) {
            if (!Files.exists(composeFile)) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Hay servicios Docker activos, pero no se encontró docker-compose.yml; el proyecto no fue eliminado");
            }
            String composeProjectName = "devvault-" + projectId.toString().replace("-", "");
            DockerComposeRunner.ProcessResult result = composeRunner.down(composeFile, composeProjectName);
            if (!result.isSuccess()) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                        "No se pudo detener Docker Compose; el proyecto no fue eliminado: " + result.output());
            }
            for (Service service : services) {
                service.updateStatus(ServiceStatus.STOPPED);
                serviceRepository.save(service);
                containerRepository.findByServiceId(service.getId()).ifPresent(container -> {
                    container.updateState("stopped");
                    containerRepository.save(container);
                });
            }
        }

        if (runtimeActive) {
            latestInstance.markStopped();
            runtimeInstanceRepository.save(latestInstance);
        }
    }
}
