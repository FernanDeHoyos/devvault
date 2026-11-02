package com.devvault.discovery.plugin;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class TechnologyEvidence {

    private TechnologyEvidence() {
    }

    public static Map<String, Object> direct(
            String name,
            String category,
            String version,
            String file,
            String property,
            String value,
            boolean primary) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("file", file);
        evidence.put("property", property);
        if (value != null && !value.isBlank()) {
            evidence.put("value", value);
        }

        Map<String, Object> finding = new LinkedHashMap<>();
        finding.put("name", name);
        finding.put("category", category);
        finding.put("version", version);
        finding.put("confidence", "DIRECT");
        finding.put("primary", primary);
        finding.put("evidence", List.of(evidence));
        return finding;
    }
}
