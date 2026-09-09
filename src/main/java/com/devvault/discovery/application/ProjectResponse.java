package com.devvault.discovery.application;

import java.util.UUID;

public record ProjectResponse(
        UUID id,
        UUID workspaceId,
        String path,
        String name,
        String language,
        String framework,
        String version,
        String status
) {
    public static ProjectResponse fromDomain(com.devvault.discovery.domain.Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getWorkspaceId(),
                project.getPath(),
                project.getName(),
                project.getLanguage(),
                project.getFramework(),
                project.getVersion(),
                project.getStatus().name()
        );
    }
}
