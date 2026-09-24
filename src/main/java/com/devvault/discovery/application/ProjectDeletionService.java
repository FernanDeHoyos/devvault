package com.devvault.discovery.application;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.devvault.discovery.domain.Project;
import com.devvault.discovery.infraestructure.ProjectRepository;
import com.devvault.runtime.application.StopProjectUseCase;
import com.devvault.shared.api.exception.ApiException;

@Service
public class ProjectDeletionService {

    private final ProjectRepository projectRepository;
    private final StopProjectUseCase stopProjectUseCase;

    public ProjectDeletionService(ProjectRepository projectRepository, StopProjectUseCase stopProjectUseCase) {
        this.projectRepository = projectRepository;
        this.stopProjectUseCase = stopProjectUseCase;
    }

    /** Removes the project and its cascaded DevVault data without touching its source directory. */
    @Transactional
    public void delete(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Proyecto no encontrado: " + projectId));

        stopProjectUseCase.stopForDeletion(projectId);
        projectRepository.delete(project);
    }
}
