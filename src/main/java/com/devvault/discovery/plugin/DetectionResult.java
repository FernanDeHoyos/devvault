package com.devvault.discovery.plugin;

import java.util.Map;

public record DetectionResult(
        String language,
        String framework,
        String version,
        Map<String, Object> rawMarkers
) {
}
