package com.devvault.discovery.plugin;

import java.util.List;

public record RunConfiguration(
        String serviceName,
        List<String> command,
        Integer port,
        int startupTimeoutSeconds
) {
    public RunConfiguration(String serviceName, List<String> command, Integer port) {
        this(serviceName, command, port, 30);
    }
}
