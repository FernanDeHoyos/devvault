package com.devvault.workspace.application.dto;

import java.time.Instant;
import java.util.UUID;

import com.devvault.workspace.domain.Workspace;


/**
 * record es una clase inmutable que representa un objeto de transferencia de datos (DTO) para la respuesta de un espacio de trabajo.
 * Los records son una característica de Java 16 que permite definir clases de datos de manera concisa y legible.
 * Representa la respuesta de un espacio de trabajo.
 *
 * @param id el id del espacio de trabajo
 * @param name el nombre del espacio de trabajo
 * @param path la ruta del espacio de trabajo
 * @param createdAt la fecha de creación del espacio de trabajo
 */
public record WorkspaceResponse(
    UUID id,
    String name,
    String path,
    Instant createdAt
) {

    /**
     * Crea un WorkspaceResponse a partir de un Workspace.
     * @param workspace el workspace del que se quiere crear la respuesta
     * @return un WorkspaceResponse con los datos del workspace
     */
    public static WorkspaceResponse from(Workspace workspace) {
        return new WorkspaceResponse(
            workspace.getId(),
            workspace.getName(),
            workspace.getPath(),
            workspace.getCreatedAt()
        );
    }
}
