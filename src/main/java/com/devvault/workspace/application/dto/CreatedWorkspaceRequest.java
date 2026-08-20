package com.devvault.workspace.application.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * record es una clase inmutable que representa un objeto de transferencia de datos (DTO) para la creación de un nuevo espacio de trabajo.
 * Los records son una característica de Java 16 que permite definir clases de datos de manera conc
 * Representa una solicitud para crear un nuevo espacio de trabajo.
 *
 * @param name el nombre del espacio de trabajo
 * @param path la ruta del espacio de trabajo
 */
public record CreatedWorkspaceRequest(
    @NotBlank(message = "Name is required") String name,
    @NotBlank(message = "Path is required") String path
){
    
}
