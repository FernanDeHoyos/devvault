package com.devvault.editor;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuración del módulo {@code editor} (prefijo {@code devvault.editor}).
 *
 * <p>Solo hay una preference en la fase 1: el editor por defecto. Vive aquí y
 * no en la base de datos a propósito, por dos razones:
 *
 * <ul>
 *   <li>Es un valor único de la <em>máquina</em>, no del proyecto. Guardarlo en
 *       PostgreSQL obligaría a una migración y a una entidad JPA para guardar
 *       un solo string.</li>
 *   <li>El proyecto ya tiene el precedente: la autenticación local vive en un
 *       archivo fuera del repositorio, no en la base de datos. Lo que describe
 *       la máquina del usuario no necesita una tabla.</li>
 * </ul>
 *
 * <p>La preferencia <em>por proyecto</em> sí es información relacional sobre un
 * proyecto y esa sí merece tabla. Se deja para una fase posterior, junto a la
 * migración {@code V12} que la soportaría.
 */
@Validated
@ConfigurationProperties(prefix = "devvault.editor")
public record EditorProperties(

        /**
         * Id del editor preferido, por ejemplo {@code vscode} o {@code intellij}.
         * Si viene vacío o no corresponde a ningún editor conocido, DevVault usa
         * el primer editor disponible que detecte en la máquina.
         */
        String defaultEditor) {

    /** Normaliza un id venido de configuración o de la petición. */
    public String normalizedDefault() {
        return defaultEditor == null ? "" : defaultEditor.trim().toLowerCase();
    }
}
