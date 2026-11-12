package com.devvault.discovery.plugin;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.io.File;
import java.nio.file.Path;

public record RunConfiguration(
        String serviceName,
        List<String> command,
        Integer port,
        int startupTimeoutSeconds,
        Path workingDirectory,
        Map<String, String> environment,
        String healthCheckUrl,
        List<String> requiredEnvironment
) {
    public RunConfiguration(String serviceName, List<String> command, Integer port, int startupTimeoutSeconds,
            Path workingDirectory, Map<String, String> environment, String healthCheckUrl) {
        this(serviceName, command, port, startupTimeoutSeconds, workingDirectory, environment, healthCheckUrl, List.of());
    }

    public List<String> missingEnvironment(Map<String, String> effectiveEnvironment) {
        Map<String, String> values = environmentForPlatform(effectiveEnvironment);
        return requiredEnvironment.stream()
                .filter(name -> values.getOrDefault(name, "").isBlank())
                .toList();
    }

    private Map<String, String> environmentForPlatform(Map<String, String> effectiveEnvironment) {
        if (File.separatorChar != '\\') {
            return effectiveEnvironment;
        }
        Map<String, String> values = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        values.putAll(effectiveEnvironment);
        values.putAll(environment);
        return values;
    }

    public RunConfiguration(String serviceName, List<String> command, Integer port, int startupTimeoutSeconds) {
        this(serviceName, command, port, startupTimeoutSeconds, null, Map.of(), null);
    }

    public RunConfiguration(String serviceName, List<String> command, Integer port, int startupTimeoutSeconds,
            Path workingDirectory, Map<String, String> environment) {
        this(serviceName, command, port, startupTimeoutSeconds, workingDirectory, environment, null);
    }

    public RunConfiguration(String serviceName, List<String> command, Integer port) {
        this(serviceName, command, port, 30, null, Map.of(), null);
    }
}
