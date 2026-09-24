package com.devvault.discovery.application.dto;

import java.util.List;

/** A statically discovered Spring route. Conditions are source annotations, not runtime evaluation. */
public record ProjectRouteResponse(
        String httpMethod,
        String path,
        String controller,
        String handler,
        String sourceFile,
        int line,
        String discoveryStatus,
        List<String> conditions) {
}
