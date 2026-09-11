package com.devvault.discovery.application;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.devvault.discovery.domain.Project;
import com.devvault.discovery.domain.ProjectProfile;
import com.devvault.discovery.domain.ProjectStatus;
import com.devvault.discovery.domain.event.WorkspaceScanRequestedEvent;
import com.devvault.discovery.infraestructure.ProjectProfileRepository;
import com.devvault.discovery.infraestructure.ProjectRepository;
import com.devvault.discovery.plugin.DetectionResult;

import jakarta.transaction.Transactional;

/**
 * Escucha WorkspaceScanRequestedEvent y ejecuta el ScannerEngine (CU-03).
 * Corre de forma asíncrona (@Async) para no bloquear la petición HTTP que
 * disparó el escaneo — coherente con el 202 Accepted del endpoint de scan.
 */
@Service 
public class ScanUseCase {
    
    private static final Logger log = LoggerFactory.getLogger(ScanUseCase.class);

    // Inyección de dependencias del motor de escaneo y repositorios
    private final ScannerEngine scannerEngine;
    private final ProjectRepository projectRepository;
    private final ProjectProfileRepository projectProfileRepository;
    private final ScanStatusTracker scanStatusTracker;

    public ScanUseCase(ScannerEngine scannerEngine, ProjectRepository projectRepository, ProjectProfileRepository projectProfileRepository, ScanStatusTracker scanStatusTracker) {
        this.scannerEngine = scannerEngine;
        this.projectRepository = projectRepository;
        this.projectProfileRepository = projectProfileRepository;
        this.scanStatusTracker = scanStatusTracker;
    }

    /**
     * Maneja la solicitud de escaneo de un workspace.
     * @param event el evento de solicitud de escaneo
     */
    @Async
    @EventListener
    public void onWorkspaceScanRequest(WorkspaceScanRequestedEvent event) {
        log.info(">>> Evento recibido. workspaceId={}, path={}", event.workspaceId(), event.workspacePath());
        scanStatusTracker.markInProgress(event.workspaceId());
 
        try {
            List<ScannerEngine.ScannedProject> scanned = scannerEngine.scanProjects(Path.of(event.workspacePath()));
            log.info(">>> Scanner encontró {} proyecto(s)", scanned.size());
 
            for (ScannerEngine.ScannedProject item : scanned) {
                log.info(">>> Detectado: {} -> {} {}", item.path(), item.detectionResult().language(), item.detectionResult().framework());
                upsertProject(event.workspaceId(), item);
            }
 
            scanStatusTracker.markCompleted(event.workspaceId(), scanned.size());
            log.info(">>> Escaneo completado y persistido.");
        } catch (Exception e) {
            scanStatusTracker.markFailed(event.workspaceId(), e.getMessage());
            log.error(">>> ERROR durante el escaneo", e);
        }
    }

    /**
     * Inserta o actualiza un proyecto en la base de datos según los resultados del escaneo.
     * @param workspaceId el ID del workspace al que pertenece el proyecto
     * @param item el proyecto escaneado
     */
    @Transactional 
    protected void upsertProject(UUID workspaceId, ScannerEngine.ScannedProject item) {
        String path = item.path().toString();
        DetectionResult detectionResult = item.detectionResult();

        // Busca un proyecto existente por workspaceId y path, 
        // actualiza si existe, o crea uno nuevo si no.
        Project project = projectRepository.findByWorkspaceIdAndPath(workspaceId, path)
                .map(existing -> {
                    existing.updateDetection(detectionResult.language(), detectionResult.framework(), detectionResult.version());
                    return existing;
                }).orElseGet(() -> new Project(
                    workspaceId,
                    item.path().getFileName().toString(),
                    path,
                    detectionResult.language(),
                    detectionResult.framework(),
                    detectionResult.version(),
                    ProjectStatus.ACTIVE
                ));
        Project savedProject = projectRepository.save(project);
        
        // Busca un perfil de proyecto existente por projectId,
        // actualiza si existe, o crea uno nuevo si no.
        ProjectProfile profile = projectProfileRepository.findByProjectId(savedProject.getId())
                .map(existingProfile -> {
                    existingProfile.refreshDetection(detectionResult.rawMarkers());
                    return existingProfile;
                }).orElseGet(() -> new ProjectProfile(
                    savedProject.getId(),
                    detectionResult.rawMarkers()
                ));
            
                projectProfileRepository.save(profile);
    }
}
