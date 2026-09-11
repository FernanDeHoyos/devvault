package com.devvault.discovery.infraestructure;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.devvault.discovery.domain.ProjectProfile;

/**
 * Repositorio para gestionar perfiles de proyectos.
 */
public interface ProjectProfileRepository extends JpaRepository<ProjectProfile, UUID> {
     Optional<ProjectProfile> findByProjectId(UUID projectId);
}
