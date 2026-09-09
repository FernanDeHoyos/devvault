package com.devvault.workspace.api;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.devvault.workspace.application.WorkspaceService;
import com.devvault.workspace.application.dto.CreatedWorkspaceRequest;
import com.devvault.workspace.application.dto.WorkspaceResponse;

import jakarta.validation.Valid;


/**
 * Controlador REST para manejar las solicitudes relacionadas con los espacios de trabajo.
 * Proporciona endpoints para crear, buscar y listar espacios de trabajo.
 */
@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {
    
    // Servicio de espacios de trabajo que contiene la lógica de negocio para manejar los espacios de trabajo.
    private final WorkspaceService workspaceService;

    public WorkspaceController(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    /**
     * Crea un nuevo espacio de trabajo.
     * @param request la solicitud de creación de un nuevo espacio de trabajo
     * @return una ResponseEntity con el estado HTTP 201 (CREATED) y el cuerpo de la respuesta del espacio de trabajo creado
     */
    @PostMapping
    public ResponseEntity<WorkspaceResponse> createWorkspace(@Valid @RequestBody CreatedWorkspaceRequest request) {
        WorkspaceResponse response = workspaceService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Obtiene todos los espacios de trabajo.
     * @return una lista con todos los espacios de trabajo
     */
    @GetMapping
    public List<WorkspaceResponse> findAll() {
        return workspaceService.findAll();
    }

    /**
     * Obtiene un espacio de trabajo por su ID.
     * @param id el ID del espacio de trabajo
     * @return el espacio de trabajo encontrado
     */
    @GetMapping("/{id}")
    public WorkspaceResponse findById(@PathVariable UUID id) {
        return workspaceService.findById(id);
    }

    /**
     * Solicita un escaneo del workspace especificado.
     * @param workspaceId el ID del workspace a escanear
     * @return una ResponseEntity con el estado HTTP 200 (OK) si la solicitud fue exitosa
     */
    @PostMapping("/{id}/scan")
    public ResponseEntity<Void> requestScan(@PathVariable UUID id) {
        workspaceService.requestScan(id);
        return ResponseEntity.ok().build();
    }
}
