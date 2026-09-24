package com.devvault.monitoring.application;

import com.devvault.monitoring.application.dto.AlertResponse;
import com.devvault.monitoring.application.dto.ContainerMetricsResponse;
import com.devvault.monitoring.domain.Alert;
import com.devvault.monitoring.domain.AlertStatus;
import com.devvault.monitoring.infrastructure.AlertRepository;
import com.devvault.runtime.domain.Container;
import com.devvault.runtime.domain.ContainerKind;
import com.devvault.runtime.domain.Service;
import com.devvault.runtime.domain.ServiceStatus;
import com.devvault.runtime.domain.event.ProjectFailedEvent;
import com.devvault.runtime.infrastructure.ContainerRepository;
import com.devvault.runtime.infrastructure.DockerClientAdapter;
import com.devvault.runtime.infrastructure.ServiceRepository;
import com.github.dockerjava.api.model.Statistics;
import com.devvault.shared.api.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@org.springframework.stereotype.Service
public class MonitoringService {
    private static final Logger log = LoggerFactory.getLogger(MonitoringService.class);
    private final AlertRepository alertRepository;
    private final ServiceRepository serviceRepository;
    private final ContainerRepository containerRepository;
    private final DockerClientAdapter dockerClientAdapter;
    private final LocalProcessMetricsProvider localProcessMetricsProvider;

    public MonitoringService(AlertRepository alertRepository, ServiceRepository serviceRepository,
                             ContainerRepository containerRepository, DockerClientAdapter dockerClientAdapter,
                             LocalProcessMetricsProvider localProcessMetricsProvider) {
        this.alertRepository = alertRepository;
        this.serviceRepository = serviceRepository;
        this.containerRepository = containerRepository;
        this.dockerClientAdapter = dockerClientAdapter;
        this.localProcessMetricsProvider = localProcessMetricsProvider;
    }

    @EventListener
    @Transactional
    public void onProjectFailed(ProjectFailedEvent event) {
        alertRepository.save(new Alert(event.projectId(), "PROJECT_FAILED", event.reason()));
    }

    public List<AlertResponse> findAlerts(AlertStatus status, UUID projectId) {
        return alertRepository.findAll().stream()
                .filter(a -> status == null || a.getStatus() == status)
                .filter(a -> projectId == null || a.getProjectId().equals(projectId))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .map(AlertResponse::from).toList();
    }

    @Transactional
    public AlertResponse resolveAlert(UUID id, boolean resolved) {
        Alert alert = alertRepository.findById(id).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "Alerta no encontrada: " + id));
        if (resolved) alert.resolve();
        return AlertResponse.from(alertRepository.save(alert));
    }

    public List<ContainerMetricsResponse> metrics(UUID projectId, UUID requestedServiceId, String requestedContainerId) {
        return serviceRepository.findByProjectId(projectId).stream()
                .filter(service -> requestedServiceId == null || requestedServiceId.equals(service.getId()))
                .map(service -> metricsFor(service, requestedContainerId))
                .filter(Objects::nonNull).toList();
    }

    private ContainerMetricsResponse metricsFor(Service service, String requestedContainerId) {
        Container container = containerRepository.findByServiceId(service.getId()).orElse(null);
        if (container == null) return null;
        if (container.getKind() == ContainerKind.LOCAL_PROCESS) {
            if (requestedContainerId != null || container.getPid() == null
                    || service.getStatus() != ServiceStatus.RUNNING) return null;
            LocalProcessMetricsProvider.Sample sample = localProcessMetricsProvider.measure(container.getPid());
            if (sample == null) return null;
            return new ContainerMetricsResponse(service.getId(), service.getName(), "LOCAL_PROCESS", null,
                    container.getPid(), sample.cpuPercent(), sample.privateMemoryMb(), Instant.now());
        }
        if (container.getKind() != ContainerKind.DOCKER || container.getDockerContainerId() == null
                || (requestedContainerId != null && !requestedContainerId.equals(container.getDockerContainerId()))) return null;
        try {
            // El estado persistido puede quedar desactualizado si el usuario detuvo
            // el contenedor por fuera de DevVault. Consulta Docker antes de pedir stats.
            if (!dockerClientAdapter.isRunning(container.getDockerContainerId())) return null;
        } catch (RuntimeException e) {
            log.debug("Se omiten métricas del contenedor Docker {} porque ya no está disponible",
                    container.getDockerContainerId(), e);
            return null;
        }

        Statistics previousStats = dockerClientAdapter.readStats(container.getDockerContainerId());
        if (previousStats != null) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.debug("Muestra de CPU interrumpida para el contenedor {}", container.getDockerContainerId());
                return null;
            }
        }
        Statistics stats = dockerClientAdapter.readStats(container.getDockerContainerId());
        if (stats == null || stats.getMemoryStats() == null || stats.getMemoryStats().getUsage() == null) return null;
        return new ContainerMetricsResponse(service.getId(), service.getName(), "DOCKER", container.getDockerContainerId(),
                null, previousStats == null ? null : cpuPercent(stats, previousStats),
                stats.getMemoryStats().getUsage() / (1024.0 * 1024.0), Instant.now());
    }

    private Double cpuPercent(Statistics current, Statistics previous) {
        if (current.getCpuStats() == null || current.getCpuStats().getCpuUsage() == null
                || current.getCpuStats().getCpuUsage().getTotalUsage() == null
                || previous.getCpuStats() == null || previous.getCpuStats().getCpuUsage() == null
                || previous.getCpuStats().getCpuUsage().getTotalUsage() == null
                || current.getCpuStats().getSystemCpuUsage() == null
                || previous.getCpuStats().getSystemCpuUsage() == null) return null;
        long cpuDelta = current.getCpuStats().getCpuUsage().getTotalUsage()
                - previous.getCpuStats().getCpuUsage().getTotalUsage();
        long systemDelta = current.getCpuStats().getSystemCpuUsage()
                - previous.getCpuStats().getSystemCpuUsage();
        if (cpuDelta < 0 || systemDelta <= 0) return null;
        long cores = current.getCpuStats().getOnlineCpus() == null ? 1 : current.getCpuStats().getOnlineCpus();
        return cpuDelta * 100.0 / systemDelta * cores;
    }
}
