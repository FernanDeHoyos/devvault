package com.devvault.discovery.application;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

import com.devvault.discovery.domain.Project;
import com.devvault.discovery.domain.ProjectProfile;
import com.devvault.discovery.domain.event.WorkspaceScanRequestedEvent;
import com.devvault.discovery.infraestructure.ProjectProfileRepository;
import com.devvault.discovery.infraestructure.ProjectRepository;
import com.devvault.discovery.plugin.DetectionResult;

import jakarta.transaction.Transactional;

public class ScanUseCase {
    
    private final ScannerEngine scannerEngine;
    private final ProjectRepository projectRepository;
    private final ProjectProfileRepository projectProfileRepository;

    public ScanUseCase(ScannerEngine scannerEngine, ProjectRepository projectRepository, ProjectProfileRepository projectProfileRepository) {
        this.scannerEngine = scannerEngine;
        this.projectRepository = projectRepository;
        this.projectProfileRepository = projectProfileRepository;
    }

    @Async
    @EventListener
    public void onWorkspaceScanRequest(WorkspaceScanRequestedEvent event) {
        List<ScannerEngine.ScannedProject> scanned = scannerEngine.scanProjects(Path.of(event.workspacePath()));
        for(ScannerEngine.ScannedProject item : scanned) {
           upsertProject(event.workspaceId(), item);
        }
    }

    @Transactional 
    protected void upsertProject(UUID workspaceId, ScannerEngine.ScannedProject item) {
        String path = item.path().toString();
        DetectionResult detectionResult = item.detectionResult();

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
                    com.devvault.discovery.domain.ProjectStatus.ACTIVE
                ));
        Project savedProject = projectRepository.save(project);
        
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
