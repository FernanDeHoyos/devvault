package com.devvault.discovery.plugin;

import java.util.List;
import java.util.Map;
import java.nio.file.Path;

public record RunConfiguration(
        String serviceName,
        List<String> command,
        Integer port,
        int startupTimeoutSeconds,
        Path workingDirectory,
        Map<String, String> environment,
        String healthCheckUrl
) {
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
