package com.devvault.runtime.application;

import com.devvault.discovery.application.ProjectLookupService;
import com.devvault.runtime.domain.Container;
import com.devvault.runtime.domain.RuntimeInstance;
import com.devvault.runtime.domain.RuntimeStatus;
import com.devvault.discovery.plugin.TechnologyPlugin;
import com.devvault.discovery.plugin.RunConfiguration;
import com.devvault.shared.api.exception.ApiException;
import com.devvault.runtime.domain.event.ProjectFailedEvent;
import com.devvault.runtime.domain.event.ProjectStartedEvent;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
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
    private final LocalProcessLogHub localProcessLogHub;
    private final LocalProcessManager localProcessManager;
    private final LocalProcessHealthProbe localProcessHealthProbe;
    private final ApplicationEventPublisher eventPublisher;

    private final List<TechnologyPlugin> technologyPlugins;

    /**
     * Suelo del tiempo de espera del arranque. El valor que declara el plugin
     * es un mínimo, no un tope: una primera ejecución de Maven o Gradle puede
     * descargar la distribución y resolver todas las dependencias, y eso supera
     * con facilidad los 120s que declaraba el plugin de Spring.
     */
    private final int baseStartupTimeoutSeconds;

    private static final Pattern ANSI_CODES = Pattern.compile("\u001B\\[[;\\d]*m");

    public StartProjectUseCase(ProjectLookupService projectLookupService,
            DockerComposeRunner composeRunner,
            DockerClientAdapter dockerClientAdapter,
            RuntimeInstanceRepository runtimeInstanceRepository,
            ServiceRepository serviceRepository,
            ContainerRepository containerRepository,
            List<TechnologyPlugin> technologyPlugins,
            LocalProcessLogHub localProcessLogHub,
            LocalProcessManager localProcessManager,
            LocalProcessHealthProbe localProcessHealthProbe,
            ApplicationEventPublisher eventPublisher,
            @Value("${devvault.runtime.startup-timeout-seconds:300}") int baseStartupTimeoutSeconds) {
        this.projectLookupService = projectLookupService;
        this.composeRunner = composeRunner;
        this.dockerClientAdapter = dockerClientAdapter;
        this.runtimeInstanceRepository = runtimeInstanceRepository;
        this.serviceRepository = serviceRepository;
        this.containerRepository = containerRepository;
        this.technologyPlugins = technologyPlugins;
        this.localProcessLogHub = localProcessLogHub;
        this.localProcessManager = localProcessManager;
        this.localProcessHealthProbe = localProcessHealthProbe;
        this.eventPublisher = eventPublisher;
        this.baseStartupTimeoutSeconds = baseStartupTimeoutSeconds;
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

        // NUEVO: aunque el estado guardado sea FAILED, el proceso del SO puede
        // seguir vivo (health check falso negativo). No relanzar en ese caso.
        if (isAnyLocalProcessStillAlive(projectId)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Ya hay un proceso local corriendo para este proyecto — detenlo antes de reiniciar");
        }
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

            // 1. Persistimos el Service PRIMERO — necesitamos su id antes de
            // arrancar el proceso, porque el log hub se indexa por serviceId.
            Service service = serviceRepository.findByProjectIdAndName(projectId, configuration.serviceName())
                    .orElseGet(() -> new Service(projectId, configuration.serviceName(), "APP", configuration.port()));
            Service savedService = serviceRepository.save(service);

            log.info(">>> Ejecutando proyecto local {} con comando: {}", projectId, configuration.command());

            ProcessBuilder processBuilder = new ProcessBuilder(configuration.command())
                    .directory(projectPath.toFile())
                    .redirectErrorStream(true);

            Process process = processBuilder.start();
            localProcessManager.register(projectId, process);
            int pid = (int) process.pid();
            String commandText = String.join(" ", configuration.command());

            log.info(">>> Proyecto local {} iniciado. PID: {}", projectId, pid);

            PortSignals signals = new PortSignals();
            drainProcessOutput(savedService.getId(), projectId, process, signals);

            Container container = containerRepository.findByServiceId(savedService.getId())
                    .map(existing -> {
                        existing.updateState("starting");
                        existing.updatePid(pid);
                        existing.updateCommand(commandText);
                        return existing;
                    })
                    .orElseGet(() -> Container.localProcess(savedService.getId(), pid, commandText, "starting"));
            containerRepository.save(container);

            int startupTimeoutSeconds = Math.max(
                    configuration.startupTimeoutSeconds(), baseStartupTimeoutSeconds);
            LocalProcessHealthProbe.Result probe = localProcessHealthProbe.awaitReady(
                    process,
                    signals,
                    configuration.port(),
                    startupTimeoutSeconds,
                    () -> localProcessLogHub.recentLines(savedService.getId()));

            boolean healthy = probe.healthy();
            Integer finalPort = probe.port() != null ? probe.port() : configuration.port();

            log.info(
                    ">>> [{}] Health check: {} | puerto detectado: {} | puerto configurado: {} | timeout: {}s",
                    projectId,
                    healthy,
                    probe.port(),
                    configuration.port(),
                    startupTimeoutSeconds);
            if (!java.util.Objects.equals(finalPort, savedService.getPort())) {
                savedService.updatePort(finalPort);
            }

            log.info(
                    ">>> [{}] Puerto final del servicio: {}",
                    projectId,
                    finalPort);
            savedService.updateStatus(healthy ? ServiceStatus.RUNNING : ServiceStatus.FAILED);
            serviceRepository.save(savedService);

            container.updateState(healthy ? "running" : "unhealthy");
            containerRepository.save(container);

            if (healthy) {
                instance.markRunning();
                eventPublisher.publishEvent(new ProjectStartedEvent(projectId));
            } else {
                String reason = probe.failureReason();
                instance.markFailed(reason);
                eventPublisher.publishEvent(new ProjectFailedEvent(projectId, reason));
            }

            return runtimeInstanceRepository.save(instance);

        } catch (IOException e) {
            log.error(">>> Error iniciando proyecto local {}", projectId, e);
            String reason = "No se pudo iniciar el proyecto: " + e.getMessage();
            instance.markFailed(reason);
            eventPublisher.publishEvent(new ProjectFailedEvent(projectId, reason));
            return runtimeInstanceRepository.save(instance);
        }
    }

    private boolean isAnyLocalProcessStillAlive(UUID projectId) {

        Process process = localProcessManager.get(projectId);

        if (process == null) {
            return false;
        }

        boolean alive = process.isAlive();

        log.info(
                ">>> Proceso local del proyecto {} | PID: {} | alive: {}",
                projectId,
                process.pid(),
                alive);

        return alive;
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
            String reason = "docker compose up falló: " + result.output();
            instance.markFailed(reason);
            log.error(">>> Fallo al iniciar {}: {}", projectId, result.output());
            eventPublisher.publishEvent(new ProjectFailedEvent(projectId, reason)); // <- nuevo
            return runtimeInstanceRepository.save(instance);
        }

        List<com.github.dockerjava.api.model.Container> dockerContainers = dockerClientAdapter
                .listContainersByComposeProject(
                        composeProjectName);

        log.info(
                ">>> Contenedores Docker encontrados para {}: {}",
                composeProjectName,
                dockerContainers.size());

        for (var dc : dockerContainers) {
            log.info(
                    ">>> Docker container encontrado | id={} | name={} | state={} | service={}",
                    dc.getId(),
                    dc.getNames() != null && dc.getNames().length > 0
                            ? dc.getNames()[0]
                            : "sin nombre",
                    dc.getState(),
                    dc.getLabels().get("com.docker.compose.service"));
        }

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
                        log.info(
                                ">>> Actualizando Container BD | serviceId={} | ID anterior={} | ID nuevo={}",
                                savedService.getId(),
                                existingContainer.getDockerContainerId(),
                                dc.getId());
                        existingContainer.updateDockerContainerId(dc.getId());
                        existingContainer.updateState(dc.getState());
                        return existingContainer;
                    })
                    .orElseGet(() -> {

                        log.info(
                                ">>> Creando Container BD | serviceId={} | ID={}",
                                savedService.getId(),
                                dc.getId());

                        return new Container(
                                savedService.getId(),
                                dc.getId(),
                                dc.getState());
                    });

            containerRepository.save(container);

            if (!healthy) {
                allHealthy = false;
                failedServiceName = serviceName;
            }
        }

        if (allHealthy) {
            instance.markRunning();
            eventPublisher.publishEvent(new ProjectStartedEvent(projectId));
        } else {
            String reason = "Servicio '" +
                            failedServiceName +
                            "' no pasó el health check";
            instance.markFailed(reason);
            eventPublisher.publishEvent(new ProjectFailedEvent(projectId, reason));
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
                .sorted(java.util.Comparator.comparingInt(TechnologyPlugin::detectionPriority).reversed())
                .filter(plugin -> plugin.detect(projectPath).isPresent())
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "No se pudo determinar la tecnología del proyecto: "
                                + projectPath));
    }

    /**
     * Lee la salida del proceso, la publica para el WebSocket de logs y la
     * analiza para detectar el puerto por el que realmente escucha.
     *
     * <p>Este hilo es también el que evita que el proceso se cuelgue: si nadie
     * consume el {@code stdout}/{@code stderr} del hijo, el pipe del sistema
     * operativo se llena y el proceso queda bloqueado sin llegar a arrancar.
     *
     * @param serviceId ID del servicio, para indexar el buffer de logs
     * @param projectId ID del proyecto, solo para el log del backend
     * @param process   proceso local ya lanzado
     * @param signals   señales de puerto alimentadas con cada línea
     */
    private void drainProcessOutput(UUID serviceId, UUID projectId, Process process, PortSignals signals) {
        Thread reader = new Thread(() -> {
            try (var bufferedReader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    String cleanLine = ANSI_CODES.matcher(line).replaceAll("");

                    log.info(">>> [{}] {}", projectId, cleanLine);
                    localProcessLogHub.publish(serviceId, cleanLine);
                    signals.observe(cleanLine);
                }
            } catch (IOException e) {
                log.debug(">>> Stream de salida del proceso local cerrado: {}", projectId);
            }
        }, "local-process-output-" + projectId);
        reader.setDaemon(true);
        reader.start();
    }

}
