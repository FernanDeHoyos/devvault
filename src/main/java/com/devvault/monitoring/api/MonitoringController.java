package com.devvault.monitoring.api;

import com.devvault.monitoring.application.MonitoringService;
import com.devvault.monitoring.application.dto.AlertResponse;
import com.devvault.monitoring.application.dto.ResolveAlertRequest;
import com.devvault.monitoring.domain.AlertStatus;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/alerts")
public class MonitoringController {
    private final MonitoringService monitoringService;

    public MonitoringController(MonitoringService monitoringService) { this.monitoringService = monitoringService; }

    @GetMapping
    public List<AlertResponse> alerts(@RequestParam(required = false) AlertStatus status,
                                     @RequestParam(required = false) UUID projectId) {
        return monitoringService.findAlerts(status, projectId);
    }

    @PatchMapping("/{id}")
    public AlertResponse resolve(@PathVariable UUID id, @Valid @RequestBody ResolveAlertRequest request) {
        return monitoringService.resolveAlert(id, request.resolved());
    }
}
