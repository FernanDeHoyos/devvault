package com.devvault.discovery.application;

import com.devvault.discovery.domain.Project;
import com.devvault.discovery.infraestructure.*;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Punto de acceso liviano para que otros módulos (ej. runtime) consulten
 * datos de Project sin importar la entidad JPA ni el repositorio de discovery
 * directamente — solo este record, desacoplado del modelo interno.
 *
 * Es una excepción documentada a "todo pasa por eventos": el EventBus es
 * ideal para notificaciones de una sola vía (workspace -> discovery), pero
 * una consulta síncrona de solo lectura como esta no encaja bien en pub/sub
 * sin volverse innecesariamente compleja para un MVP.
 */
@Service
public class ProjectLookupService {

    public record ProjectSummary(UUID id, String name, String path) {
    }

    private final ProjectRepository projectRepository;

    public ProjectLookupService(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    public Optional<ProjectSummary> findById(UUID projectId) {
        return projectRepository.findById(projectId)
                .map(p -> new ProjectSummary(p.getId(), p.getName(), p.getPath()));
    }

    /**
     * IDs de los proyectos que pertenecen a un Workspace.
     *
     * <p>Lo necesita el borrado de Workspace para poder detener sus runtimes
     * antes de borrar las filas: la cascada del esquema borra la base de datos,
     * pero no sabe nada de los contenedores de Docker ni de los procesos locales
     * que quedarían sin control.
     *
     * @param workspaceId Workspace cuyos proyectos se quieren localizar
     * @return los ids, vacía si el Workspace no tiene proyectos registrados
     */
    public List<UUID> findIdsByWorkspaceId(UUID workspaceId) {
        return projectRepository.findByWorkspaceId(workspaceId).stream()
                .map(Project::getId)
                .toList();
    }
}