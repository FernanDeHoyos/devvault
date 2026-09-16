package com.devvault.runtime.application;

import com.devvault.discovery.application.ProjectLookupService;
import com.devvault.runtime.domain.RuntimeInstance;
import com.devvault.runtime.infrastructure.DockerComposeRunner;
import com.devvault.runtime.infrastructure.RuntimeInstanceRepository;
import com.devvault.shared.api.exception.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.UUID;

@Component
public class StopProjectUseCase {

    private final ProjectLookupService projectLookupService;
    private final DockerComposeRunner composeRunner;
    private final RuntimeInstanceRepository runtimeInstanceRepository;

    public StopProjectUseCase(ProjectLookupService projectLookupService,
            DockerComposeRunner composeRunner,
            RuntimeInstanceRepository runtimeInstanceRepository) {
        this.projectLookupService = projectLookupService;
        this.composeRunner = composeRunner;
        this.runtimeInstanceRepository = runtimeInstanceRepository;
    }

    /**
     * Detiene un proyecto.
     * 
     * @param projectId ID del proyecto
     * @return Instancia del runtime
     */
    public RuntimeInstance execute(UUID projectId) {
        ProjectLookupService.ProjectSummary project = projectLookupService.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Proyecto no encontrado: " + projectId));

        // Busca la instancia activa
        RuntimeInstance instance = runtimeInstanceRepository.findFirstByProjectIdOrderByStartedAtDesc(projectId)
                .filter(RuntimeInstance::isActive)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                        "El proyecto no tiene una instancia activa para detener"));

        // Obtiene el path del archivo docker-compose.yml
        Path composeFile = Path.of(project.path()).resolve("docker-compose.yml");

        // Construye el nombre del proyecto de Docker Compose.
        String composeProjectName = "devvault-" + projectId.toString().replace("-", "");

        // Ejecuta docker compose down
        composeRunner.down(composeFile, composeProjectName);

        instance.markStopped();
        return runtimeInstanceRepository.save(instance);
    }
}