package com.devvault.runtime.application.dto;

import com.devvault.runtime.domain.RuntimeInstance;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO para la respuesta de una instancia del runtime.
 */
public record RuntimeInstanceResponse(
        UUID id,
        UUID projectId,
        String overallStatus,
        Instant startedAt,
        Instant stoppedAt,
        String failureReason) {
    public static RuntimeInstanceResponse from(RuntimeInstance instance) {
        return new RuntimeInstanceResponse(
                instance.getId(),
                instance.getProjectId(),
                instance.getOverallStatus().name(),
                instance.getStartedAt(),
                instance.getStoppedAt(),
                instance.getFailureReason());
    }
}