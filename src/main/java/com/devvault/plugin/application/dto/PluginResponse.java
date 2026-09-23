package com.devvault.plugin.application.dto;

import com.devvault.plugin.domain.PluginDescriptor;

import java.util.UUID;

public record PluginResponse(
        UUID id,
        String name,
        String targetMarkerFiles,
        String version,
        boolean enabled
) {
    public static PluginResponse from(PluginDescriptor descriptor) {
        return new PluginResponse(
                descriptor.getId(),
                descriptor.getName(),
                descriptor.getTargetMarkerFiles(),
                descriptor.getVersion(),
                descriptor.isEnabled()
        );
    }
}