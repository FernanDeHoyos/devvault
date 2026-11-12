package com.devvault.discovery.application.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ProjectIntelligenceResponse(
        UUID projectId,
        Instant scannedAt,
        List<Technology> stack,
        List<Module> modules,
        List<Dependency> dependencies,
        List<Database> databases,
        List<Tool> tools,
        List<Configuration> configurations,
        List<ServiceComponent> services,
        ProjectMap map,
        List<ArchitecturePattern> architecturePatterns,
        Map<String, List<String>> environmentVariables,
        List<String> warnings) {

    public record Technology(
            String id,
            String name,
            String category,
            String version,
            String confidence,
            boolean primary,
            List<Evidence> evidence) {
    }

    public record Evidence(String file, String property, String value) {
    }

    public record ArchitecturePattern(String type, String scope, String confidence, List<Evidence> evidence) {
    }

    public record Module(String id, String name, String path, String type, Evidence evidence) {
    }

    public record Dependency(
            String id,
            String name,
            String version,
            String scope,
            String manager,
            Evidence evidence) {
    }

    public record Database(String id, String name, String status, Evidence evidence) {
    }

    public record Tool(String id, String name, String version, Evidence evidence) {
    }

    public record Configuration(String id, String file, String purpose) {
    }

    public record ServiceComponent(
            String id, String name, String type, String image, Evidence evidence, List<String> dependsOn) {
    }

    public record ProjectMap(List<Node> nodes, List<Edge> edges) {
    }

    public record Node(
            String id,
            String name,
            String type,
            String category,
            String version,
            List<Evidence> evidence) {
    }

    public record Edge(
            String id,
            String source,
            String target,
            String relationship,
            String confidence,
            List<Evidence> evidence) {
    }
}
