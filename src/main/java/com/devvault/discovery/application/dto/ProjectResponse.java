package com.devvault.discovery.application.dto;

import java.util.UUID;

import com.devvault.discovery.application.GitInfoReader;
import com.devvault.discovery.domain.Project;
import com.devvault.discovery.domain.ProjectStatus;


/**
 * DTO de respuesta para representar un proyecto detectado en un workspace.
 * Contiene información relevante del proyecto, incluyendo su estado y detalles de Git.
 */
public record ProjectResponse(
    UUID id,
    UUID workspaceId,
    String name,
    String path,
    String language,
    String framework,
    String version,
    ProjectStatus status,
    String gitBranch,
    String gitShortCommitHash
) {

    /**
     * Crea un ProjectResponse a partir de un objeto de dominio Project.
     * @param project el proyecto de dominio
     * @return un ProjectResponse con la información del proyecto
     */
    public static ProjectResponse fromDomain(Project project) {
        return fromDomain(project, null);
            
    }

    /**
     * Crea un ProjectResponse a partir de un objeto de dominio Project y la información de Git.
     * @param project el proyecto de dominio
     * @param gitInfo la información de Git del proyecto
     * @return un ProjectResponse con la información del proyecto y Git
     */
    public static ProjectResponse fromDomain(Project project, GitInfoReader.GitInfo gitInfo) {
        return new ProjectResponse(
            project.getId(),
            project.getWorkspaceId(),
            project.getName(),
            project.getPath(),
            project.getLanguage(),
            project.getFramework(),
            project.getVersion(),
            project.getStatus(),
            gitInfo != null ? gitInfo.branch() : null,
            gitInfo != null ? gitInfo.shortCommitHash() : null
        );
    }
}
