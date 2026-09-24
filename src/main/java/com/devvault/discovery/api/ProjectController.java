package com.devvault.discovery.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.devvault.monitoring.application.MonitoringService;
import com.devvault.monitoring.application.dto.ContainerMetricsResponse;

import com.devvault.discovery.application.dto.ProjectResponse;
import com.devvault.discovery.application.GitInfoReader;
import com.devvault.discovery.application.RouteScanner;
import com.devvault.discovery.application.ProjectDeletionService;
import com.devvault.discovery.application.dto.ProjectRouteResponse;
import com.devvault.discovery.domain.Project;
import com.devvault.discovery.domain.ProjectProfile;
import com.devvault.discovery.infraestructure.ProjectRepository;
import com.devvault.discovery.infraestructure.ProjectProfileRepository;

@RestController 
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final MonitoringService monitoringService;

    // Inyección de dependencia del repositorio de proyectos
    private final ProjectRepository projectRepository;

    // Inyección de dependencia del lector de información de Git
    private final GitInfoReader gitInfoReader;

    private final RouteScanner routeScanner;
    private final ProjectDeletionService projectDeletionService;
    private final ProjectProfileRepository projectProfileRepository;
    
    public ProjectController(
        ProjectRepository projectRepository,
        GitInfoReader gitInfoReader,
        MonitoringService monitoringService,
        RouteScanner routeScanner,
        ProjectDeletionService projectDeletionService,
        ProjectProfileRepository projectProfileRepository) {
        this.projectRepository = projectRepository;
        this.gitInfoReader = gitInfoReader;
        this.monitoringService = monitoringService;
        this.routeScanner = routeScanner;
        this.projectDeletionService = projectDeletionService;
        this.projectProfileRepository = projectProfileRepository;
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

    Map<UUID, List<String>> technologiesByProjectId = projects.isEmpty()
            ? Map.of()
            : projectProfileRepository.findByProjectIdIn(projects.stream().map(Project::getId).toList()).stream()
                    .collect(Collectors.toMap(ProjectProfile::getProjectId, this::technologiesFrom));

    return projects.stream()
            .map(project -> {
                GitInfoReader.GitInfo gitInfo =
                        gitInfoReader.read(Path.of(project.getPath()))
                                .orElse(null);

                return ProjectResponse.fromDomain(project, gitInfo,
                        technologiesByProjectId.getOrDefault(project.getId(), List.of()));
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
    public ProjectResponse findById(@PathVariable UUID id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Project not found with id: " + id));

                GitInfoReader.GitInfo gitInfo = gitInfoReader.read(Path.of(project.getPath())).orElse(null);
        List<String> technologies = projectProfileRepository.findByProjectId(id)
                .map(this::technologiesFrom)
                .orElse(List.of());
        return ProjectResponse.fromDomain(project, gitInfo, technologies);
    }


    @GetMapping("/{id}/metrics")
    public List<ContainerMetricsResponse> metrics(@PathVariable UUID id,
                                                   @RequestParam(required = false) UUID serviceId,
                                                   @RequestParam(required = false) String containerId) {
        return monitoringService.metrics(id, serviceId, containerId);
    }

    /** Returns statically discovered HTTP mappings in the project's source files. */
    @GetMapping("/{id}/routes")
    public List<ProjectRouteResponse> routes(@PathVariable UUID id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Project not found with id: " + id));
        return routeScanner.scan(Path.of(project.getPath()));
    }

    /** Deletes the project from DevVault while preserving its files on disk. */
    @DeleteMapping("/{id}")
    public org.springframework.http.ResponseEntity<Void> delete(@PathVariable UUID id) {
        projectDeletionService.delete(id);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    private List<String> technologiesFrom(ProjectProfile profile) {
        if (profile.getRawMarkers() == null) {
            return List.of();
        }
        Object values = profile.getRawMarkers().get("technologies");
        if (!(values instanceof List<?> technologies)) {
            return List.of();
        }
        return technologies.stream().map(String::valueOf).toList();
    }
    
}
