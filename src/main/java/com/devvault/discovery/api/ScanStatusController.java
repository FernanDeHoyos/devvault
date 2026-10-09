package com.devvault.discovery.api;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devvault.discovery.application.ScanStatusTracker;
import com.devvault.discovery.application.dto.ScanStatusResponse;

/**
 * Estado del último escaneo de un Workspace.
 *
 * <p>Vive bajo {@code /workspaces} por coherencia con el contrato de API,
 * aunque lo gestione discovery: el path de la API no tiene que calcar la
 * estructura de paquetes.
 */
@RestController
@RequestMapping("/api/v1/workspaces")
public class ScanStatusController {

    private final ScanStatusTracker scanStatusTracker;

    public ScanStatusController(ScanStatusTracker scanStatusTracker) {
        this.scanStatusTracker = scanStatusTracker;
    }

    /**
     * Estado del escaneo del Workspace indicado.
     *
     * <p>Un Workspace que nunca se ha escaneado devuelve {@code NOT_STARTED}, no
     * 404. La UI hace polling de esta ruta en cada tarjeta de la pantalla de
     * workspaces, así que devolver 404 para el caso normal de "aún no he
     * escaneado" llenaba la consola de errores sin que hubiera nada que
     * arreglar. El propio enum ya definía ese valor.
     *
     * @param id el Workspace consultado
     * @return el estado del escaneo, con {@code NOT_STARTED} si no hay registro
     */
    @GetMapping("/{id}/scan/status")
    public ScanStatusResponse status(@PathVariable("id") UUID id) {
        return ScanStatusResponse.fromSnapshot(scanStatusTracker.statusOrNotStarted(id));
    }
}