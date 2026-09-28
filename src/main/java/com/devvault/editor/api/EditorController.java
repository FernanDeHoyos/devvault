package com.devvault.editor.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.devvault.editor.EditorProperties;
import com.devvault.editor.application.EditorCatalog;
import com.devvault.editor.application.EditorDescriptor;
import com.devvault.editor.application.OpenProjectInEditorUseCase;
import com.devvault.editor.application.dto.EditorListResponse;
import com.devvault.editor.application.dto.EditorListResponse.EditorInfo;
import com.devvault.editor.application.dto.OpenedProjectResponse;

/**
 * API del módulo {@code editor}.
 *
 * <p>Las dos rutas viven aquí y no en {@code ProjectController} a propósito: el
 * proyecto {@code discovery} no debe saber nada de editores. El módulo editor
 * llega al proyecto por {@code ProjectLookupService}, que expone un record
 * desacoplado en vez de la entidad JPA.
 *
 * <p>Las dos rutas caen bajo {@code /api/**}, así que heredan la autenticación
 * por JWT sin tocar la configuración de seguridad.
 */
@RestController
public class EditorController {

    private final EditorCatalog catalog;
    private final OpenProjectInEditorUseCase openProjectInEditor;
    private final EditorProperties properties;

    public EditorController(EditorCatalog catalog,
            OpenProjectInEditorUseCase openProjectInEditor,
            EditorProperties properties) {
        this.catalog = catalog;
        this.openProjectInEditor = openProjectInEditor;
        this.properties = properties;
    }

    /**
     * Lista los editores conocidos y cuáles están disponibles en esta máquina.
     * No requiere arrancar nada: solo comprueba que el ejecutable exista.
     */
    @GetMapping("/api/v1/editors")
    public EditorListResponse listEditors() {
        String configuredId = properties.normalizedDefault();
        Optional<EditorDescriptor> defaultEditor = catalog.resolveDefault(configuredId);
        String defaultId = defaultEditor.map(EditorDescriptor::id).orElse(null);

        List<EditorInfo> editors = catalog.all().stream()
                .map(editor -> {
                    Optional<Path> executable = catalog.resolveExecutable(editor);
                    return new EditorInfo(
                            editor.id(),
                            editor.displayName(),
                            executable.isPresent(),
                            executable.map(Object::toString).orElse(null),
                            editor.id().equals(defaultId));
                })
                .toList();

        return new EditorListResponse(editors, defaultId, configuredId.isEmpty() ? null : configuredId);
    }

    /**
     * Abre un proyecto en el editor.
     *
     * <p>Responde {@code 200} y no {@code 202} a propósito. El {@code 202
     * Accepted} del contrato significa "aceptado, consulta el resultado más
     * tarde", y aquí no hay nada que consultar: el editor no genera un
     * {@code RuntimeInstance} ni tiene endpoint de estado. La operación está
     * terminada cuando el proceso se ha lanzado. Además el cuerpo de la
     * respuesta dice qué editor se usó realmente, que es justo lo que la UI
     * muestra para confirmar, y el cliente descarta el cuerpo en un 202.
     *
     * <p>El editor va como query param y no en un cuerpo JSON a propósito. Con
     * {@code @RequestBody} opcional, una llamada sin cuerpo sigue exigiendo la
     * cabecera {@code Content-Type} y responde {@code 415} antes de llegar al
     * caso de uso, que es una trampa innecesaria para un parámetro escalar
     * único. Como query param el endpoint se invoca sin argumentos, y además
     * queda coherente con {@code GET /projects}, que ya filtra por
     * {@code workspaceId} y {@code status} de la misma forma.
     */
    @PostMapping("/api/v1/projects/{id}/open")
    public OpenedProjectResponse open(
            @PathVariable("id") UUID id,
            @RequestParam(name = "editorId", required = false) String editorId) {
        return OpenedProjectResponse.from(openProjectInEditor.execute(id, editorId));
    }
}
