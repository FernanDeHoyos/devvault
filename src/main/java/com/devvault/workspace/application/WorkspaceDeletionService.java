package com.devvault.workspace.application;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.devvault.discovery.application.ProjectLookupService;
import com.devvault.discovery.application.ScanStatusTracker;
import com.devvault.runtime.application.StopProjectUseCase;
import com.devvault.shared.api.exception.ApiException;
import com.devvault.workspace.domain.Workspace;
import com.devvault.workspace.infrastructure.WorkspaceRepository;

import jakarta.transaction.Transactional;

/**
 * Elimina un Workspace y todo lo que DevVault sabe de él.
 *
 * <h2>Qué se borra y qué no</h2>
 *
 * <p>Se borra únicamente la información de DevVault. <strong>Los archivos del
 * disco no se tocan</strong>: borrar un Workspace es olvidarse de una carpeta,
 * no borrarla. Por eso la interfaz lo dice de forma explícita al confirmar.
 *
 * <p>La cascada la pone el esquema: {@code projects.workspace_id} es
 * {@code ON DELETE CASCADE}, y desde ahí cuelgan en cascada project_profiles,
 * services, containers, runtime_instances, alerts, automation_rules y
 * git_fetch_events. No hace falta borrar esas tablas a mano, y por tanto esta
 * funcionalidad no necesita ninguna migración.
 *
 * <h2>Por qué se detienen los runtimes antes</h2>
 *
 * <p>La cascada borra filas, pero no sabe nada del sistema operativo. Un
 * contenedor de Docker Compose o un proceso local seguirían vivos sin las filas
 * que DevVault usa para controlarlos, y quedarían huérfanos: puertos ocupados y
 * procesos que nadie puede parar. Por eso se reutiliza
 * {@code StopProjectUseCase.stopForDeletion}, el mismo camino que usa el borrado
 * de un proyecto individual.
 *
 * <p>Ese cruce entre módulos es legítimo: se importa la capa
 * {@code application} del otro, nunca su {@code domain}. Es exactamente lo que
 * hace {@code ProjectDeletionService} con el runtime.
 */
@Service
public class WorkspaceDeletionService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceDeletionService.class);

    private final WorkspaceRepository workspaceRepository;
    private final ProjectLookupService projectLookupService;
    private final StopProjectUseCase stopProjectUseCase;
    private final ScanStatusTracker scanStatusTracker;

    public WorkspaceDeletionService(WorkspaceRepository workspaceRepository,
            ProjectLookupService projectLookupService,
            StopProjectUseCase stopProjectUseCase,
            ScanStatusTracker scanStatusTracker) {
        this.workspaceRepository = workspaceRepository;
        this.projectLookupService = projectLookupService;
        this.stopProjectUseCase = stopProjectUseCase;
        this.scanStatusTracker = scanStatusTracker;
    }

    /**
     * Borra el Workspace y sus datos dependientes.
     *
     * @param workspaceId Workspace a eliminar
     */
    @Transactional
    public void delete(UUID workspaceId) {

        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Workspace no encontrado: " + workspaceId));

        List<UUID> projectIds = projectLookupService.findIdsByWorkspaceId(workspaceId);
        log.info("Eliminando workspace {} con {} proyectos, en {}",
                workspaceId, projectIds.size(), workspace.getPath());

        // Detener antes de borrar. Si algo se resiste a pararse, stopForDeletion
        // lanza y la transacción no llega a borrar nada: es preferible dejar el
        // Workspace intacto que perder el control de un proceso huérfano.
        for (UUID projectId : projectIds) {
            stopProjectUseCase.stopForDeletion(projectId);
        }

        workspaceRepository.delete(workspace);

        // El tracker de escaneos vive en memoria y no expira entradas. Sin
        // limpiarla quedaría un snapshot de un Workspace que ya no existe.
        scanStatusTracker.forget(workspaceId);

        log.info("Workspace {} eliminado de DevVault. Los archivos en {} se conservan.",
                workspaceId, workspace.getPath());
    }
}