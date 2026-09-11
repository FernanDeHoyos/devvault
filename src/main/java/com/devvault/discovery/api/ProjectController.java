package com.devvault.discovery.api;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.devvault.discovery.application.dto.ProjectResponse;
import com.devvault.discovery.application.GitInfoReader;
import com.devvault.discovery.domain.Project;
import com.devvault.discovery.infraestructure.ProjectRepository;

@RestController 
@RequestMapping("/api/v1/projects")
public class ProjectController {

    // Inyección de dependencia del repositorio de proyectos
    private final ProjectRepository projectRepository;

    // Inyección de dependencia del lector de información de Git
    private final GitInfoReader gitInfoReader;
    
    public ProjectController(ProjectRepository projectRepository, GitInfoReader gitInfoReader) {
        this.projectRepository = projectRepository;
        this.gitInfoReader = gitInfoReader;
    }

    /**
     * Obtiene todos los proyectos, filtrando por workspaceId si se proporciona.
     * @param workspaceId el ID del espacio de trabajo para filtrar los proyectos (opcional)
     * @return una lista de ProjectResponse que representan los proyectos encontrados
     */
    @GetMapping
public List<ProjectResponse> findAll(
        @RequestParam(value = "workspaceId", required = false) UUID workspaceId) {

    List<Project> projects = (workspaceId != null)
            ? projectRepository.findByWorkspaceId(workspaceId)
            : projectRepository.findAll();

    return projects.stream()
            .map(project -> {
                GitInfoReader.GitInfo gitInfo =
                        gitInfoReader.read(Path.of(project.getPath()))
                                .orElse(null);

                return ProjectResponse.fromDomain(project, gitInfo);
            })
            .toList();
}

    /**
     * Obtiene un proyecto por su ID.
     * @param id el ID del proyecto a buscar
     * @return un ProjectResponse que representa el proyecto encontrado
     * @throws RuntimeException si el proyecto no es encontrado
     */
    @GetMapping("/{id}")
    public ProjectResponse findById(@RequestParam UUID id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Project not found with id: " + id));

                GitInfoReader.GitInfo gitInfo = gitInfoReader.read(Path.of(project.getPath())).orElse(null);
        return ProjectResponse.fromDomain(project, gitInfo);
    }
    
}