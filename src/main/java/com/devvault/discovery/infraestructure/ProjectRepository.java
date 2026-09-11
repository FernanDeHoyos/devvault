package com.devvault.discovery.infraestructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.devvault.discovery.domain.Project;

/**
 * Repositorio para gestionar proyectos.
 */
public interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findByWorkspaceIdAndPath(UUID workspaceId, String path);

    List<Project> findByWorkspaceId(UUID workspaceId);
}
