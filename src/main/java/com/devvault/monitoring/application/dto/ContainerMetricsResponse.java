package com.devvault.monitoring.application.dto;

import java.time.Instant;
import java.util.UUID;

public record ContainerMetricsResponse(UUID serviceId, String serviceName, String runtimeKind,
                                       String containerId, Integer pid, Double cpuPercent,
                                       double memMb, Instant capturedAt) { }
