package com.devvault.runtime.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;


public record ActiveRuntimeResponse(
        UUID projectId,
        String overallStatus,
        Instant startedAt,
        List<ServiceResponse> services
) {
}
