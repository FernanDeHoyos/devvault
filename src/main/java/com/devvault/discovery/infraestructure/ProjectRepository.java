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
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
    @org.springframework.data.jpa.repository.Query("select project from Project project where project.id = :id")
    Optional<Project> lockForRuntime(@org.springframework.data.repository.query.Param("id") UUID id);

    Optional<Project> findByWorkspaceIdAndPath(UUID workspaceId, String path);

    List<Project> findByWorkspaceId(UUID workspaceId);
}
