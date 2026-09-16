package com.devvault.runtime.application;

import com.devvault.discovery.application.ProjectLookupService;
import com.devvault.runtime.domain.Container;
import com.devvault.runtime.domain.RuntimeInstance;
import com.devvault.runtime.domain.RuntimeStatus;
import com.devvault.discovery.plugin.TechnologyPlugin;
import com.devvault.discovery.plugin.RunConfiguration;
import com.devvault.shared.api.exception.ApiException;
import com.github.dockerjava.api.model.ContainerPort;
import com.devvault.runtime.domain.ServiceStatus;
import com.devvault.runtime.infrastructure.ContainerRepository;
import com.devvault.runtime.domain.Service;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private final List<TechnologyPlugin> technologyPlugins;

    private static final Pattern PORT_PATTERN = Pattern.compile("localhost:(\\d{4,5})");

    public StartProjectUseCase(ProjectLookupService projectLookupService,
            DockerComposeRunner composeRunner,
            DockerClientAdapter dockerClientAdapter,
            RuntimeInstanceRepository runtimeInstanceRepository,
            ServiceRepository serviceRepository,
            ContainerRepository containerRepository,
            List<TechnologyPlugin> technologyPlugins) {
        this.projectLookupService = projectLookupService;
        this.composeRunner = composeRunner;
        this.dockerClientAdapter = dockerClientAdapter;
        this.runtimeInstanceRepository = runtimeInstanceRepository;
        this.serviceRepository = serviceRepository;
        this.containerRepository = containerRepository;
        this.technologyPlugins = technologyPlugins;
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
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "Proyecto no encontrado: " + projectId));

        // Idempotencia
        var existing = runtimeInstanceRepository
                .findFirstByProjectIdOrderByStartedAtDesc(projectId);
        if (existing.isPresent()
                && existing.get().getOverallStatus() == RuntimeStatus.RUNNING) {
            log.info(
                    ">>> Proyecto {} ya está corriendo, devolviendo instancia existente",
                    projectId);
            return existing.get();
        }

        Path projectPath = Path.of(project.path());
        Path composeFile = projectPath.resolve("docker-compose.yml");
        boolean hasDockerCompose = Files.exists(composeFile);

        log.info(
                ">>> Proyecto: {} | docker-compose.yml existe: {}",
                projectPath,
                hasDockerCompose);

        if (hasDockerCompose) {
            return executeDockerCompose(
                    projectId,
                    project,
                    composeFile);
        }
        return executeLocal(projectId, project);
    }

    /**
     * Ejecuta un proyecto sin docker-compose.yml
     * 
     * @param projectId ID del proyecto
     * @param project   Resumen del proyecto
     * @return Instancia del runtime
     */
    private RuntimeInstance executeLocal(UUID projectId, ProjectLookupService.ProjectSummary project) {

        RuntimeInstance instance = new RuntimeInstance(projectId);
        runtimeInstanceRepository.save(instance);
        Path projectPath = Path.of(project.path());

        try {
            TechnologyPlugin technologyPlugin = findPlugin(projectPath);

            RunConfiguration configuration = technologyPlugin
                    .getRunConfiguration(projectPath)
                    .orElseThrow(() -> new ApiException(
                            HttpStatus.UNPROCESSABLE_ENTITY,
                            "La tecnología detectada no tiene una configuración de ejecución local: " + projectPath));

            log.info(">>> Ejecutando proyecto local {} con comando: {}", projectId, configuration.command());

            ProcessBuilder processBuilder = new ProcessBuilder(configuration.command())
                    .directory(projectPath.toFile())
                    .redirectErrorStream(true);

            Process process = processBuilder.start();
            int pid = (int) process.pid();
            String commandText = String.join(" ", configuration.command());

            log.info(">>> Proyecto local {} iniciado. PID: {}", projectId, pid);

            AtomicInteger detectedPort = new AtomicInteger(-1);
            drainProcessOutput(projectId, process, detectedPort);

            Service service = serviceRepository.findByProjectIdAndName(projectId, configuration.serviceName())
                    .orElseGet(() -> new Service(projectId, configuration.serviceName(), "APP", configuration.port()));
            Service savedService = serviceRepository.save(service);

            Container container = containerRepository.findByServiceId(savedService.getId())
                    .map(existing -> {
                        existing.updateState("starting");
                        existing.updatePid(pid);
                        existing.updateCommand(commandText);
                        return existing;
                    })
                    .orElseGet(() -> Container.localProcess(savedService.getId(), pid, commandText, "starting"));
            containerRepository.save(container);

            // Health check: espera a que el proceso reporte un puerto real; si nunca
            // lo reporta, cae de vuelta al puerto asumido por el plugin como plan B.
            boolean healthy = waitUntilLocalProcessHealthy(process, detectedPort, configuration.port());

            int finalPort = detectedPort.get() > 0 ? detectedPort.get() : configuration.port();
            if (finalPort != savedService.getPort()) {
                // el puerto real terminó siendo distinto al asumido — lo corregimos
                savedService.updatePort(finalPort); // <- ver nota abajo sobre este método
            }

            savedService.updateStatus(healthy ? ServiceStatus.RUNNING : ServiceStatus.FAILED);
            serviceRepository.save(savedService);

            container.updateState(healthy ? "running" : "unhealthy");
            containerRepository.save(container);

            if (healthy) {
                instance.markRunning();
            } else {
                instance.markFailed("El proceso local no respondió en ningún puerto detectable");
            }

            return runtimeInstanceRepository.save(instance);

        } catch (IOException e) {
            log.error(">>> Error iniciando proyecto local {}", projectId, e);
            instance.markFailed("No se pudo iniciar el proyecto: " + e.getMessage());
            return runtimeInstanceRepository.save(instance);
        }
    }

    /**
     * Ejecuta un proyecto con docker-compose.yml
     * 
     * @param projectId   ID del proyecto
     * @param project     Resumen del proyecto
     * @param composeFile Path del docker-compose.yml
     * @return Instancia del runtime
     */
    private RuntimeInstance executeDockerCompose(UUID projectId,
            ProjectLookupService.ProjectSummary project,
            Path composeFile) {

        RuntimeInstance instance = new RuntimeInstance(projectId);
        runtimeInstanceRepository.save(instance);

        String composeProjectName = "devvault-" + projectId.toString().replace("-", "");
        DockerComposeRunner.ProcessResult result = composeRunner.up(composeFile, composeProjectName);

        if (!result.isSuccess()) {
            instance.markFailed(
                    "docker compose up falló: " + result.output());
            log.error(">>> Fallo al iniciar {}: {}", projectId, result.output());

            return runtimeInstanceRepository.save(instance);
        }

        List<com.github.dockerjava.api.model.Container> dockerContainers = dockerClientAdapter
                .listContainersByComposeProject(
                        composeProjectName);

        boolean allHealthy = true;
        String failedServiceName = null;

        for (com.github.dockerjava.api.model.Container dc : dockerContainers) {
            String serviceName = dc.getLabels()
                    .getOrDefault(
                            "com.docker.compose.service",
                            dc.getId());

            String type = guessType(dc.getImage());
            Integer port = extractFirstPort(dc.getPorts());
            Service service = serviceRepository
                    .findByProjectIdAndName(projectId, serviceName)
                    .orElseGet(() -> new Service(
                            projectId,
                            serviceName,
                            type,
                            port));

            Service savedService = serviceRepository.save(service);
            boolean healthy = waitUntilRunning(dc.getId());
            savedService.updateStatus(
                    healthy
                            ? ServiceStatus.RUNNING
                            : ServiceStatus.FAILED);

            serviceRepository.save(savedService);
            Container container = containerRepository
                    .findByServiceId(savedService.getId())
                    .map(existingContainer -> {
                        existingContainer.updateState(
                                dc.getState());
                        return existingContainer;
                    })
                    .orElseGet(() -> new Container(
                            savedService.getId(),
                            dc.getId(),
                            dc.getState()));

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
                            "' no pasó el health check");
        }
        return runtimeInstanceRepository.save(instance);
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

    /**
     * Busca el plugin de tecnología para el proyecto.
     * 
     * @param projectPath Path del proyecto
     * @return Plugin de tecnología
     */
    private TechnologyPlugin findPlugin(Path projectPath) {

        return technologyPlugins.stream()
                .filter(plugin -> plugin.detect(projectPath).isPresent())
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "No se pudo determinar la tecnología del proyecto: "
                                + projectPath));
    }

    /**
     * Health check para procesos locales: confirma que el proceso del SO
     * sigue vivo Y, si se conoce el puerto declarado, que ya acepta conexiones.
     * Equivalente a waitUntilRunning() de la rama Docker.
     */
    private boolean waitUntilLocalProcessHealthy(Process process, Integer expectedPort) {
        for (int attempt = 0; attempt < HEALTH_CHECK_ATTEMPTS; attempt++) {
            if (!process.isAlive()) {
                return false; // se cayó (ej. error de sintaxis, dependencia faltante)
            }
            if (expectedPort == null || isPortOpen(expectedPort)) {
                return true;
            }
            sleep();
        }
        return false;
    }

    /**
     * Verifica si un puerto está abierto.
     * 
     * @param port Puerto a verificar
     * @return true si el puerto está abierto, false en caso contrario
     */
    private boolean isPortOpen(int port) {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("localhost", port), 500);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Lee la salida del proceso y detecta el puerto.
     * 
     * @param projectId    ID del proyecto
     * @param process      Proceso
     * @param detectedPort Puerto detectado
     */
    private void drainProcessOutput(UUID projectId, Process process, AtomicInteger detectedPort) {
        Thread reader = new Thread(() -> {
            try (var bufferedReader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    log.info(">>> [{}] {}", projectId, line);

                    Matcher matcher = PORT_PATTERN.matcher(line);
                    if (matcher.find()) {
                        detectedPort.set(Integer.parseInt(matcher.group(1)));
                    }
                }
            } catch (IOException e) {
                log.debug(">>> Stream de salida del proceso local cerrado: {}", projectId);
            }
        });
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Espera a que el proceso local esté corriendo.
     * 
     * @param process      Proceso
     * @param detectedPort Puerto detectado
     * @param assumedPort  Puerto asumido
     * @return true si el proceso está corriendo, false en caso contrario
     */
    private boolean waitUntilLocalProcessHealthy(Process process, AtomicInteger detectedPort, Integer assumedPort) {
        for (int attempt = 0; attempt < HEALTH_CHECK_ATTEMPTS; attempt++) {
            if (!process.isAlive()) {
                return false;
            }

            Integer portToCheck = detectedPort.get() > 0 ? detectedPort.get() : assumedPort;
            if (portToCheck != null && isPortOpen(portToCheck)) {
                return true;
            }
            sleep();
        }
        return false;
    }
}