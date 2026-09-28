package com.devvault.editor.application.dto;

import java.util.List;

/**
 * Lo que devuelve {@code GET /api/v1/editors}.
 *
 * @param editors      editores conocidos, instalados o no
 * @param defaultId    editor que se usará si la petición no pide otro
 * @param configuredId editor configurado en {@code devvault.editor.default},
 *                     que puede no estar instalado y por eso no coincidir con
 *                     {@code defaultId}
 */
public record EditorListResponse(List<EditorInfo> editors, String defaultId, String configuredId) {

    /**
     * @param id            identificador estable
     * @param displayName   nombre para la UI
     * @param available     si se encontró el ejecutable en esta máquina
     * @param executablePath ruta resuelta, o {@code null} si no está instalado
     * @param isDefault     si es el que se usaría por defecto
     */
    public record EditorInfo(String id, String displayName, boolean available,
            String executablePath, boolean isDefault) {
    }
}
