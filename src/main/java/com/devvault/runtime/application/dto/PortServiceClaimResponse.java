package com.devvault.runtime.application.dto;

import java.util.UUID;

public record PortServiceClaimResponse(
        UUID serviceId,
        String serviceName,
        UUID projectId,
        String projectName,
        String serviceStatus,
        String runtimeKind) {
}
