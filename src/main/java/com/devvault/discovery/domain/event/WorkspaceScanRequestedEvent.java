package com.devvault.discovery.domain.event;

import java.util.UUID;

/**
 * Publicado por WorkspaceService cuando el usuario dispara un escaneo (CU-03).
 * El módulo discovery lo escucha sin que workspace conozca su existencia —
 * esta es la única forma permitida de comunicación entre módulos (ver
 * DevVault-Analisis-Diseno.md, sección 8, "regla de oro").
 */

public record WorkspaceScanRequestedEvent(UUID workspaceId, String workspacePath) {
    
}
