package com.devvault.discovery.plugin;

import java.util.List;

public record RunConfiguration(
        String serviceName,
        List<String> command,
        Integer port
) {
}