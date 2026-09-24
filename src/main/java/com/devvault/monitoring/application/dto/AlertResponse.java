package com.devvault.monitoring.application.dto;

import com.devvault.monitoring.domain.Alert;

import java.time.Instant;
import java.util.UUID;

public record AlertResponse(UUID id, UUID projectId, String type, String message,
                            String status, Instant createdAt, Instant resolvedAt) {
    public static AlertResponse from(Alert alert) {
        return new AlertResponse(alert.getId(), alert.getProjectId(), alert.getType(),
                alert.getMessage(), alert.getStatus().name(), alert.getCreatedAt(), alert.getResolvedAt());
    }
}
