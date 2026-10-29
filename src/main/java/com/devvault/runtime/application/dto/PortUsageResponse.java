package com.devvault.runtime.application.dto;

import java.util.List;

public record PortUsageResponse(
        int port,
        String status,
        boolean conflict,
        String conflictReason,
        List<PortServiceClaimResponse> services,
        List<PortOwnerResponse> listeners,
        boolean canOpen,
        String url) {
}
