package com.devvault.discovery.api;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.devvault.discovery.application.ProjectResponse;
import com.devvault.discovery.domain.Project;
import com.devvault.discovery.infraestructure.ProjectRepository;

@RestController 
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectRepository projectRepository;
    
    public ProjectController(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    @GetMapping
    public List<ProjectResponse> findAll(@RequestParam (required = false) UUID workspaceId) {
        List<Project> projects = (workspaceId != null) ? projectRepository.findByWorkspaceId(workspaceId) : projectRepository.findAll();

        return projects.stream().map(ProjectResponse::fromDomain).toList();
    }

    @GetMapping("/{id}")
    public ProjectResponse findById(@RequestParam UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new RuntimeException("Project not found with id: " + projectId));
        return ProjectResponse.fromDomain(project);
    }
    
}