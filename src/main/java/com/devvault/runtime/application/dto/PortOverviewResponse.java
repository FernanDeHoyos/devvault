package com.devvault.runtime.application.dto;

import java.time.Instant;
import java.util.List;

public record PortOverviewResponse(
        boolean listenerScanAvailable,
        Instant scannedAt,
        List<PortUsageResponse> ports) {
}
