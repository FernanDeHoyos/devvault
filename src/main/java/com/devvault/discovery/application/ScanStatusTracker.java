package com.devvault.discovery.application;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Estado del último escaneo por Workspace (RF de scan status, DevVault-API-Design.md
 * sección Workspace). Simplificación consciente del MVP 0.1: se guarda en memoria,
 * no en una tabla "scans" — se pierde si la app se reinicia a mitad de un escaneo.
 * Suficiente para validar el concepto; si hace falta persistirlo entre reinicios,
 * se promueve a una entidad JPA real en v0.2 sin cambiar el contrato del endpoint.
 */

@Component 
public class ScanStatusTracker {
    
    // Enum que representa el estado de un escaneo para un Workspace dado.
    public enum ScanStatus {
        NOT_STARTED,
        IN_PROGRESS,
        COMPLETED,
        FAILED
    }

    // Snapshot inmutable del estado de un escaneo para un Workspace dado.
    public record Snapshot(
        ScanStatus status, 
        int projectsFound, 
        Instant startedAt, 
        Instant finishedAt, 
        String errorMessage) {}

        // Mapa concurrente que mantiene el estado de escaneo por WorkspaceId.
        private final Map<UUID, Snapshot> statuses = new ConcurrentHashMap<>();

        /**
         * Marca un escaneo como en progreso para un Workspace dado,
         * inicializando el número de proyectos encontrados a 0 y registrando la hora de inicio
         * @param workspaceId el ID del Workspace para el cual se está iniciando el escaneo
         */
        public void markInProgress(UUID workspaceId) {
            statuses.put(workspaceId, new Snapshot(
                ScanStatus.IN_PROGRESS,
                0, Instant.now(), 
                null, 
                null));
        }

        /**
         * Marca un escaneo como completado para un Workspace dado,
         * actualizando el número de proyectos encontrados y registrando la hora de finalización
         * @param workspaceId el ID del Workspace para el cual se está marcando como completado
         * @param projectsFound el número de proyectos encontrados durante el escaneo
         */
        public void markCompleted(UUID workspaceId, int projectsFound) {
            statuses.computeIfPresent(workspaceId, (id, snapshot) -> new Snapshot(
                ScanStatus.COMPLETED,
                projectsFound,
                snapshot.startedAt(),
                Instant.now(),
                snapshot.errorMessage()
            ));
        }

        /**
         * Marca un escaneo como fallido para un Workspace dado,
         * registrando la hora de finalización y el mensaje de error
         * @param workspaceId el ID del Workspace para el cual se está marcando como fallido
         * @param errorMessage el mensaje de error que describe la razón del fallo
         */
        public void markFailed(UUID workspaceId, String errorMessage) {
            statuses.computeIfPresent(workspaceId, (id, snapshot) -> new Snapshot(
                ScanStatus.FAILED,
                snapshot.projectsFound(),
                snapshot.startedAt(),
                Instant.now(),
                errorMessage
            ));
        }

        /**
         * Obtiene el estado actual del escaneo para un Workspace dado.
         * @param workspaceId el ID del Workspace para el cual se desea obtener el estado
         * @return un Optional que contiene el Snapshot del estado del escaneo si existe, o vacío si no existe
         */
        public Optional<Snapshot> getStatus(UUID workspaceId) {
            return Optional.ofNullable(statuses.get(workspaceId));
        }
}
