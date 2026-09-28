package com.devvault.editor.application.dto;

import java.util.UUID;

import com.devvault.editor.application.OpenProjectInEditorUseCase;

/**
 * Respuesta de {@code POST /api/v1/projects/{id}/open}.
 *
 * @param projectId      proyecto abierto
 * @param projectName    nombre del proyecto
 * @param editorId       editor realmente usado
 * @param editorName     nombre legible del editor
 * @param executablePath ejecutable que se lanzó
 */
public record OpenedProjectResponse(UUID projectId, String projectName, String editorId,
        String editorName, String executablePath) {

    public static OpenedProjectResponse from(OpenProjectInEditorUseCase.OpenedProject opened) {
        return new OpenedProjectResponse(
                opened.projectId(),
                opened.projectName(),
                opened.editorId(),
                opened.editorName(),
                opened.executablePath());
    }
}
