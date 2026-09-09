package com.devvault.workspace.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.devvault.shared.api.exception.ApiException;
import com.devvault.workspace.application.dto.CreatedWorkspaceRequest;
import com.devvault.workspace.application.dto.WorkspaceResponse;
import com.devvault.workspace.domain.Workspace;
import com.devvault.workspace.infrastructure.WorkspaceRepository;

import jakarta.transaction.Transactional;

@Service
public class WorkspaceService {
    
    // Usuario local placeholder mientras auth real no está activa (ver V1__create_workspace.sql y HU-01)
    private static final UUID LOCAL_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    // Repositorio de workspaces para interactuar con la base de datos
    private final WorkspaceRepository workspaceRepository;
    private final ApplicationEventPublisher eventPublisher;

    // Constructor de la clase WorkspaceService que recibe un WorkspaceRepository como parámetro.
    // Este constructor permite la inyección de dependencias, lo que facilita las pruebas y la mantenibilidad del código.
    public WorkspaceService(WorkspaceRepository workspaceRepository, ApplicationEventPublisher eventPublisher) {
        this.workspaceRepository = workspaceRepository;
        this.eventPublisher = eventPublisher;
    }


    /**
     * Solicita un escaneo del workspace especificado.
     * @param workspaceId el ID del workspace a escanear
     * @throws ApiException si el workspace no es encontrado
     */
    public void requestScan(UUID workspaceId) {
        Workspace workspace = workspaceRepository.findById(workspaceId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Workspace not found with Id: " + workspaceId));
        eventPublisher.publishEvent(new com.devvault.discovery.domain.event.WorkspaceScanRequestedEvent(workspaceId, workspace.getPath()));
    }

    /**
     * Crea un nuevo espacio de trabajo.
     * @param reques la solicitud de creación de un nuevo es pacio de trabajo
     * @return la respuesta del espacio de trabajo creado
     * @throws ApiException si el path no es accesible o ya está en uso
     * 
     * Transactional es una anotación que indica que el método debe ejecutarse dentro de una transacción. Si ocurre un error, la transacción se revertirá.
     */
    @Transactional
    public WorkspaceResponse create(CreatedWorkspaceRequest request){
        NormalizeWorkspacePath(request.path());
        validatePathNotUsed(request.path());

        // Crea un nuevo Workspace con el ID de usuario local, el nombre y el path proporcionados en la solicitud
        Workspace workspace = new Workspace(LOCAL_USER_ID, request.name(), request .path());
        // Guarda el nuevo Workspace en la base de datos y devuelve la respuesta correspondiente
        Workspace saved = workspaceRepository.save(workspace);
        return WorkspaceResponse.from(saved);
    }

    /**
     * Busca todos los espacios de trabajo.
     * @return una lista de respuestas de espacios de trabajo
     */
    public List<WorkspaceResponse> findAll() {
        return workspaceRepository.findAll().stream()
                .map(WorkspaceResponse::from)
                .toList();
    }

    /**
     * Busca un espacio de trabajo por su ID.
     * @param id el ID del espacio de trabajo a buscar
     * @return la respuesta del espacio de trabajo encontrado
     * @throws ApiException si el espacio de trabajo no es encontrado
     */
    public WorkspaceResponse findById(UUID id) {
        Workspace workspace = workspaceRepository.findById(id)
        // orElseThrow lanza una excepción si el valor no está presente en el Optional. 
        // En este caso, si no se encuentra un Workspace con el ID dado, 
        // se lanza una ApiException con un estado HTTP 404 (NOT_FOUND) y 
        // un mensaje que indica que el Workspace no fue encontrado.  
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Workspace not found with Id: " + id)); 
        return WorkspaceResponse.from(workspace);
    }

    private String NormalizeWorkspacePath(String rowpath) {
        // Normaliza la ruta del espacio de trabajo para evitar problemas de formato
        Path path = Path.of(rowpath)
        .toAbsolutePath()
        .normalize();

        if(!Files.exists(path)){
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "The specified path does not exist." + path);
        }

        if(!Files.isDirectory(path)){
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "The specified path is not a directory." + path);
        }

        try {
            return path.toRealPath().toString();
        } catch (IOException e) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Error occurred while normalizing the path." + path);
        }
       
    }

    /**
     * Valida que la ruta sea accesible y sea un directorio.
     * @param path la ruta a validar
     * @throws ApiException si la ruta no es accesible o no es un directorio
     */
    /* private void validatePathIsAccessible(String path) {
        Path pathObj = Path.of(path);
        if (!Files.exists(pathObj) || !Files.isDirectory(pathObj)) {
            // Lanza una ApiException con un estado HTTP 422 (UNPROCESSABLE_ENTITY) 
            // y un mensaje que indica que la ruta no es accesible o no es un directorio
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Path is not accessible or is not a directory: " + path);
        }
    } */

    /**
     * Valida que la ruta no esté siendo utilizada por otro espacio de trabajo.
     * @param path la ruta a validar
     * @throws ApiException si la ruta ya está en uso por otro espacio de trabajo
     */
    private void validatePathNotUsed(String path) {
        if (workspaceRepository.existsByPath(path)) {
            // Lanza una ApiException con un estado HTTP 409 (CONFLICT)
            // y un mensaje que indica que la ruta ya está en uso por otro espacio de trabajo
            throw new ApiException(HttpStatus.CONFLICT, "Path is already used by another workspace: " + path);
        }
    }
}
