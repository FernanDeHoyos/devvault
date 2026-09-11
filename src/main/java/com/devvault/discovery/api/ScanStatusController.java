package com.devvault.discovery.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devvault.discovery.application.ScanStatusTracker;
import com.devvault.discovery.application.dto.ScanStatusResponse;

/**
 * Vive bajo /workspaces por coherencia con DevVault-API-Design.md, aunque
 * el dato lo gestiona discovery — el path de la API no tiene que calcar
 * la estructura de paquetes.
 */
@RestController 
@RequestMapping("/api/v1/workspaces")
public class ScanStatusController {

    private final ScanStatusTracker scanStatusTracker;
    
    public ScanStatusController(ScanStatusTracker scanStatusTracker) {
        this.scanStatusTracker = scanStatusTracker;
    }   

    /**
     * Obtiene el estado del escaneo para un Workspace dado.
     * @param id el ID del Workspace para el cual se desea obtener el estado del escaneo
     * @return un ScanStatusResponse que representa el estado actual del escaneo
     * @throws RuntimeException si no se encuentra ningún estado de escaneo para el Workspace dado
     */
    @GetMapping("/{id}/scan/status")
    public ScanStatusResponse status(@PathVariable UUID id) {
        return scanStatusTracker.getStatus(id)
            .map(ScanStatusResponse::fromSnapshot)
            .orElseThrow(() -> new RuntimeException("No scan status found for workspaceId: " + id));
    }
}
