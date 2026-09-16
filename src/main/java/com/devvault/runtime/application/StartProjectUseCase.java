package com.devvault.runtime.application;

import com.devvault.discovery.application.ProjectLookupService;
import com.devvault.runtime.domain.Container;
import com.devvault.runtime.domain.RuntimeInstance;
import com.devvault.runtime.domain.RuntimeStatus;
import com.devvault.runtime.domain.Service;
import com.devvault.shared.api.exception.ApiException;
import com.github.dockerjava.api.model.ContainerPort;
import com.devvault.runtime.domain.ServiceStatus;
import com.devvault.runtime.infrastructure.ContainerRepository;
import com.devvault.runtime.infrastructure.DockerClientAdapter;
import com.devvault.runtime.infrastructure.DockerComposeRunner;
import com.devvault.runtime.infrastructure.RuntimeInstanceRepository;
import com.devvault.runtime.infrastructure.ServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Implementa CU-05 (DevVault-Analisis-Diseno.md): start/stop/restart de
 * proyectos, con las reglas de negocio de idempotencia y health check
 * ya definidas en el diseño.
 */
@Component
public class StartProjectUseCase {

    private static final Logger log = LoggerFactory.getLogger(StartProjectUseCase.class);
    private static final int HEALTH_CHECK_ATTEMPTS = 10;
    private static final long HEALTH_CHECK_INTERVAL_MS = 3000;

    private final ProjectLookupService projectLookupService;
    private final DockerComposeRunner composeRunner;
    private final DockerClientAdapter dockerClientAdapter;
    private final RuntimeInstanceRepository runtimeInstanceRepository;
    private final ServiceRepository serviceRepository;
    private final ContainerRepository containerRepository;

    public StartProjectUseCase(ProjectLookupService projectLookupService,
            DockerComposeRunner composeRunner,
            DockerClientAdapter dockerClientAdapter,
            RuntimeInstanceRepository runtimeInstanceRepository,
            ServiceRepository serviceRepository,
            ContainerRepository containerRepository) {
        this.projectLookupService = projectLookupService;
        this.composeRunner = composeRunner;
        this.dockerClientAdapter = dockerClientAdapter;
        this.runtimeInstanceRepository = runtimeInstanceRepository;
        this.serviceRepository = serviceRepository;
        this.containerRepository = containerRepository;
    }

    /**
     * Inicia un proyecto.
     * 
     * @param projectId ID del proyecto
     * @return Instancia del runtime
     */
    @Transactional
    public RuntimeInstance execute(UUID projectId) {
        ProjectLookupService.ProjectSummary project = projectLookupService.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Proyecto no encontrado: " + projectId));

        // Idempotencia (regla de negocio de Runtime Management): si ya está corriendo,
        // no relanzar
        var existing = runtimeInstanceRepository.findFirstByProjectIdOrderByStartedAtDesc(projectId);
        if (existing.isPresent() && existing.get().getOverallStatus() == RuntimeStatus.RUNNING) {
            log.info(">>> Proyecto {} ya está corriendo, devolviendo instancia existente", projectId);
            return existing.get();
        }

        // Validación de que exista el docker-compose.yml
        Path composeFile = Path.of(project.path()).resolve("docker-compose.yml");
        boolean hasDockerCompose = Files.exists(composeFile);

    // Por ahora solamente detectamos el modo de ejecución.
    // La ejecución local la implementaremos después.
    if (!hasDockerCompose) {
       return executeDockerCompose(projectId, project, composeFile);
    }

       return executeLocal(projectId, project);
    }

    /*
     * Espera a que el contenedor esté corriendo.
     * 
     * @param dockerContainerId ID del contenedor
     * 
     * @return true si el contenedor está corriendo, false en caso contrario
     */
    private boolean waitUntilRunning(String dockerContainerId) {
        for (int attempt = 0; attempt < HEALTH_CHECK_ATTEMPTS; attempt++) {
            if (dockerClientAdapter.isRunning(dockerContainerId)) {
                return true;
            }
            sleep();
        }
        return false;
    }

    /*
     * Espera a que el contenedor esté corriendo.
     * 
     * @param dockerContainerId ID del contenedor
     */
    private void sleep() {
        try {
            Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Heurística simple por nombre de imagen — suficiente para el MVP (RF de
     * Service.type).
     */
    private String guessType(String image) {
        String lower = image.toLowerCase();
        if (lower.contains("postgres") || lower.contains("mysql") || lower.contains("mongo"))
            return "DATABASE";
        if (lower.contains("redis") || lower.contains("memcached"))
            return "CACHE";
        if (lower.contains("rabbitmq") || lower.contains("kafka"))
            return "QUEUE";
        return "APP";
    }

    /**
     * Extrae el primer puerto del contenedor.
     * 
     * @param ports Puertos del contenedor
     * @return Puerto del contenedor
     */
    private Integer extractFirstPort(ContainerPort[] ports) {
        if (ports == null)
            return null;
        for (ContainerPort p : ports) {
            if (p.getPublicPort() != null)
                return p.getPublicPort();
        }
        return null;
    }

    private RuntimeInstance executeDockerCompose(
        UUID projectId,
        ProjectLookupService.ProjectSummary project,
        Path composeFile) {

    RuntimeInstance instance = new RuntimeInstance(projectId);
    runtimeInstanceRepository.save(instance);

    String composeProjectName =
            "devvault-" + projectId.toString().replace("-", "");

    DockerComposeRunner.ProcessResult result =
            composeRunner.up(composeFile, composeProjectName);

    if (!result.isSuccess()) {
        instance.markFailed(
                "docker compose up falló: " + result.output()
        );

        log.error(
                ">>> Fallo al iniciar {}: {}",
                projectId,
                result.output()
        );

        return runtimeInstanceRepository.save(instance);
    }

    List<com.github.dockerjava.api.model.Container> dockerContainers =
            dockerClientAdapter.listContainersByComposeProject(
                    composeProjectName
            );

    boolean allHealthy = true;
    String failedServiceName = null;

    for (com.github.dockerjava.api.model.Container dc : dockerContainers) {

        String serviceName = dc.getLabels()
                .getOrDefault(
                        "com.docker.compose.service",
                        dc.getId()
                );

        String type = guessType(dc.getImage());

        Integer port = extractFirstPort(dc.getPorts());

        Service service = serviceRepository
                .findByProjectIdAndName(projectId, serviceName)
                .orElseGet(() ->
                        new Service(
                                projectId,
                                serviceName,
                                type,
                                port
                        )
                );

        Service savedService = serviceRepository.save(service);

        boolean healthy = waitUntilRunning(dc.getId());

        savedService.updateStatus(
                healthy
                        ? ServiceStatus.RUNNING
                        : ServiceStatus.FAILED
        );

        serviceRepository.save(savedService);

        Container container = containerRepository
                .findByServiceId(savedService.getId())
                .map(existingContainer -> {

                    existingContainer.updateState(
                            dc.getState()
                    );

                    return existingContainer;
                })
                .orElseGet(() ->
                        new Container(
                                savedService.getId(),
                                dc.getId(),
                                dc.getState()
                        )
                );

        containerRepository.save(container);

        if (!healthy) {
            allHealthy = false;
            failedServiceName = serviceName;
        }
    }

    if (allHealthy) {
        instance.markRunning();
    } else {
        instance.markFailed(
                "Servicio '" +
                failedServiceName +
                "' no pasó el health check"
        );
    }

    return runtimeInstanceRepository.save(instance);
}

private RuntimeInstance executeLocal(
        UUID projectId,
        ProjectLookupService.ProjectSummary project) {

    RuntimeInstance instance = new RuntimeInstance(projectId);
    runtimeInstanceRepository.save(instance);

    Path projectPath = Path.of(project.path());

    try {

        ProcessBuilder processBuilder = new ProcessBuilder(
                "npm",
                "run",
                "dev"
        );

        processBuilder
                .directory(projectPath.toFile())
                .redirectErrorStream(true);

        Process process = processBuilder.start();

        log.info(
                ">>> Proyecto local {} iniciado. PID: {}",
                projectId,
                process.pid()
        );

        instance.markRunning();

        return runtimeInstanceRepository.save(instance);

    } catch (IOException e) {

        log.error(
                ">>> Error iniciando proyecto local {}",
                projectId,
                e
        );

        instance.markFailed(
                "No se pudo iniciar el proyecto: " + e.getMessage()
        );

        return runtimeInstanceRepository.save(instance);
    }
}
}