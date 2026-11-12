package com.devvault.monitoring.api;

import com.devvault.monitoring.application.MonitoringService;
import com.devvault.monitoring.application.dto.ContainerMetricsResponse;
import com.devvault.runtime.domain.RuntimeStatus;
import com.devvault.runtime.infrastructure.RuntimeInstanceRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.*;

@RestController
public class RuntimeMetricsController {
    private final MonitoringService monitoring;
    private final RuntimeInstanceRepository runtimes;
    public RuntimeMetricsController(MonitoringService monitoring, RuntimeInstanceRepository runtimes) {
        this.monitoring = monitoring;
        this.runtimes = runtimes;
    }
    public record ProjectMetrics(UUID projectId, List<ContainerMetricsResponse> metrics, String error) {}
    @GetMapping("/api/v1/runtime/metrics")
    public List<ProjectMetrics> metrics() {
        return runtimes.findByOverallStatus(RuntimeStatus.RUNNING).stream()
                .map(instance -> instance.getProjectId()).distinct().map(id -> {
                    try { return new ProjectMetrics(id, monitoring.metrics(id, null, null), null); }
                    catch (RuntimeException e) { return new ProjectMetrics(id, List.of(), "No se pudieron medir los servicios del proyecto."); }
                }).toList();
    }
}
