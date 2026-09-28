package com.devvault.editor.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.devvault.discovery.application.ProjectLookupService;
import com.devvault.shared.api.exception.ApiException;
import com.devvault.editor.EditorProperties;

/**
 * Abre un proyecto en el editor de código, como un proceso desacoplado y
 * supervisado por el sistema operativo.
 *
 * <h2>Por qué NO es un RuntimeInstance</h2>
 *
 * <p>Un editor parece un proceso local, y las abstracciones del módulo
 * {@code runtime} invitan a modelarlo así. Sería un error, y no por estilo sino
 * por consecuencias concretas:
 *
 * <ul>
 *   <li>Si se creara un {@code RuntimeInstance}, el proyecto aparecería como
 *       "en ejecución" y {@code POST /stop} lo detendría —matando tu editor
 *       con la ventana abierta y el trabajo sin guardar.</li>
 *   <li>Si se registrara en {@code LocalProcessManager}, quedaría bajo control
 *       de DevVault, que es justo lo contrario de lo que se quiere: el editor
 *       sobrevive a que DevVault se cierre.</li>
 *   <li>Si se publicara {@code ProjectStartedEvent}, se dispararían las reglas
 *       de automatización y las alertas de "proyecto corriendo" serían falsas.</li>
 *   <li>Si se drenara su salida con {@code drainProcessOutput}, el buffer se
 *       llenaría de ruido: un editor escribe indefinidamente y nunca termina.
 *       Ese drenaje existe para procesos <em>supervisados</em>, cuyo log
 *       interesa recuperar.</li>
 * </ul>
 *
 * <p>Un editor no se supervisa: se lanza y se olvida. El sistema operativo es
 * su dueño, que es justo lo que {@code ProcessBuilder} garantiza al no
 * conservar el handle.
 *
 * <h2>Por qué no se ejecuta una shell</h2>
 *
 * <p>El comando se arma como {@code List<String>} y se pasa a
 * {@code ProcessBuilder}, nunca como cadena con shell. La ruta del proyecto
 * viene de la base de datos pero originalmente la escribió el usuario al crear
 * el workspace, y una ruta con espacios o comillas rompería un comando de
 * shell — o peor, permitiría inyectar otro comando.
 */
@Component
public class OpenProjectInEditorUseCase {

    private static final Logger log = LoggerFactory.getLogger(OpenProjectInEditorUseCase.class);

    private final ProjectLookupService projectLookupService;
    private final EditorCatalog catalog;
    private final EditorProperties properties;

    public OpenProjectInEditorUseCase(ProjectLookupService projectLookupService,
            EditorCatalog catalog,
            EditorProperties properties) {
        this.projectLookupService = projectLookupService;
        this.catalog = catalog;
        this.properties = properties;
    }

    /**
     * @param projectId proyecto a abrir
     * @param editorId  editor pedido explícitamente, o vacío para usar el
     *                  configurado
     */
    public OpenedProject execute(UUID projectId, String editorId) {

        // 1. El proyecto tiene que existir. Se consulta por ProjectLookupService
        //    y no por la entidad de discovery, para no acoplar este módulo al
        //    dominio de otro (regla de oro del modular monolith).
        ProjectLookupService.ProjectSummary project = projectLookupService.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "Proyecto no encontrado: " + projectId));

        // 2. La ruta viene de la base, pero la base puede quedar obsoleta: la
        //    carpeta se borró o se movió desde el último escaneo. Se verifica
        //    en disco en vez de confiar en lo guardado.
        Path projectPath = Path.of(project.path());
        if (!Files.isDirectory(projectPath)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "La carpeta del proyecto ya no existe: " + projectPath
                            + ". Vuelve a escanear el workspace para actualizar DevVault.");
        }

        // 3. Editor pedido -> configurado -> primer disponible.
        EditorDescriptor editor = resolveEditor(editorId);
        Optional<Path> executable = catalog.resolveExecutable(editor);
        if (executable.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "El editor '" + editor.displayName() + "' no está disponible en este equipo. "
                            + "Editores detectados: " + availableEditorNames()
                            + ". Configura otro con la variable DEVVAULT_DEFAULT_EDITOR.");
        }

        launch(editor, executable.get(), projectPath);

        log.info("Proyecto {} abierto en {} ({})", projectId, editor.displayName(), executable.get());
        return new OpenedProject(projectId, project.name(), editor.id(), editor.displayName(),
                executable.get().toString());
    }

    /**
     * Respeta el editor pedido si existe y está instalado; si no, cae al
     * configurado y luego al primer disponible, en vez de fallar.
     */
    private EditorDescriptor resolveEditor(String editorId) {
        if (editorId != null && !editorId.isBlank()) {
            return catalog.byId(editorId)
                    .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                            "Editor desconocido: '" + editorId + "'. Disponibles: " + allEditorNames()));
        }
        return catalog.resolveDefault(properties.normalizedDefault())
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No se encontró ningún editor de código instalado. Instala Visual Studio Code, "
                                + "IntelliJ IDEA, Sublime Text o Zed y vuelve a intentarlo."));
    }

    private void launch(EditorDescriptor editor, Path executable, Path projectPath) {
        List<String> command = buildCommand(executable, projectPath);
        try {
            ProcessBuilder builder = new ProcessBuilder(command)
                    .directory(projectPath.toFile())
                    // DESCARTAR la salida no es opcional. Si nadie consume el
                    // pipe y el editor escribe lo suficiente, se llenaría y el
                    // proceso se quedaría bloqueado esperando, que es
                    // exactamente el problema que ya se sufrió con los procesos
                    // locales en la v0.2. Descartarlo lo hace imposible.
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD);

            // No se espera al proceso ni se guarda el handle a propósito: el
            // editor debe sobrevivir al cierre de DevVault.
            builder.start();

            log.debug("Lanzado {} con el comando {}", editor.id(), command);
        } catch (IOException e) {
            // El archivo existía al resolver pero falló el arranque: editor
            // roto, permisos, antivirus. Es un 422 y no un 500, porque el
            // problema es del entorno, no del servidor.
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No se pudo lanzar " + editor.displayName() + ": " + e.getMessage());
        }
    }

    /**
     * Arma el comando con los mismos cuidados que usa
     * {@code SpringBootPlugin} para Maven y Gradle: en Windows los shims
     * {@code .cmd} y {@code .bat} no son ejecutables, y el sistema exige
     * envolverlos en {@code cmd.exe /c}.
     */
    private List<String> buildCommand(Path executable, Path projectPath) {
        List<String> command = new ArrayList<>();
        String fileName = executable.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

        if (windows && (fileName.endsWith(".cmd") || fileName.endsWith(".bat"))) {
            command.add("cmd.exe");
            command.add("/c");
        }
        command.add(executable.toString());
        command.add(projectPath.toString());
        return List.copyOf(command);
    }

    private String availableEditorNames() {
        List<String> names = catalog.all().stream()
                .filter(editor -> catalog.resolveExecutable(editor).isPresent())
                .map(EditorDescriptor::displayName)
                .toList();
        return names.isEmpty() ? "ninguno" : String.join(", ", names);
    }

    private String allEditorNames() {
        return String.join(", ", catalog.all().stream().map(EditorDescriptor::displayName).toList());
    }

    /** Resultado de abrir el proyecto, para que la UI confirme qué se abrió. */
    public record OpenedProject(UUID projectId, String projectName, String editorId,
            String editorName, String executablePath) {
    }
}
