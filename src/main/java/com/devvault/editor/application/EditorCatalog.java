package com.devvault.editor.application;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * Catálogo cerrado de editores que DevVault sabe abrir, y resolución del
 * ejecutable de cada uno en la máquina donde corre.
 *
 * <p>Los candidatos están en orden de preferencia. En Windows el shim
 * {@code .cmd} suele estar en el PATH pero el ejecutable real en
 * {@code %LOCALAPPDATA%}, por eso ambos aparecen. Cada candidato se intenta
 * hasta que uno resuelva.
 *
 * <p>Los editores de terminal (Neovim, Vim) no están y no se van a añadir sin
 * decidir antes cómo se les abre una ventana de terminal de forma fiable: con
 * {@code ProcessBuilder} y sin consola adjunta, mueren de inmediato.
 */
@Component
public class EditorCatalog {

    private static final List<EditorDescriptor> EDITORS = List.of(
            new EditorDescriptor("vscode", "Visual Studio Code", List.of(
                    // El shim que instala VS Code en el PATH.
                    "code.cmd",
                    "code",
                    "%LOCALAPPDATA%\\Programs\\Microsoft VS Code\\bin\\code.cmd",
                    "%LOCALAPPDATA%\\Programs\\Microsoft VS Code\\Code.exe",
                    "/usr/bin/code",
                    "/usr/share/code/code",
                    "/snap/bin/code")),

            new EditorDescriptor("vscode-insiders", "Visual Studio Code Insiders", List.of(
                    "code-insiders.cmd",
                    "code-insiders",
                    "%LOCALAPPDATA%\\Programs\\Microsoft VS Code Insiders\\bin\\code-insiders.cmd")),

            new EditorDescriptor("intellij", "IntelliJ IDEA", List.of(
                    // IntelliJ casi nunca está en el PATH: se instala en una
                    // carpeta cuyo nombre incluye la versión, de ahí el comodín.
                    "idea64",
                    "idea64.exe",
                    "%ProgramFiles%\\JetBrains\\IntelliJ IDEA *\\bin\\idea64.exe",
                    "%ProgramFiles%\\IntelliJ IDEA *\\bin\\idea64.exe",
                    "/opt/idea/bin/idea.sh",
                    "/snap/bin/idea.sh")),

            new EditorDescriptor("sublime", "Sublime Text", List.of(
                    "subl.exe",
                    "subl",
                    "%ProgramFiles%\\Sublime Text\\sublime_text.exe",
                    "/usr/bin/subl",
                    "/opt/sublime_text/sublime_text")),

            new EditorDescriptor("zed", "Zed", List.of(
                    "zed",
                    "%LOCALAPPDATA%\\Zed\\zed.exe",
                    "/usr/bin/zed")),

            // Último recurso deliberado: existe en cualquier Windows, así que
            // garantiza que la función abra algo en vez de fallar.
            new EditorDescriptor("notepad", "Bloc de notas", List.of(
                    "notepad.exe",
                    "notepad")));

    private final EditorExecutableResolver resolver;

    public EditorCatalog(EditorExecutableResolver resolver) {
        this.resolver = resolver;
    }

    /** @return todos los editores conocidos, instalados o no */
    public List<EditorDescriptor> all() {
        return EDITORS;
    }

    public Optional<EditorDescriptor> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String normalized = id.trim().toLowerCase();
        return EDITORS.stream().filter(e -> e.id().equals(normalized)).findFirst();
    }

    /**
     * Resuelve el ejecutable de un editor en esta máquina.
     *
     * @return la ruta del primer candidato que exista, o vacío si el editor no
     *         está instalado
     */
    public Optional<Path> resolveExecutable(EditorDescriptor editor) {
        for (String candidate : editor.executables()) {
            Optional<Path> resolved = resolver.resolve(candidate);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return Optional.empty();
    }

    /**
     * El editor por defecto: el configurado si está instalado; si no, el
     * primero disponible del catálogo.
     *
     * <p>Degradar en vez de fallar es la regla RNF-06 del proyecto: un editor
     * mal configurado no debe dejar al usuario sin poder abrir nada.
     *
     * @param configuredDefault id configurado, puede venir vacío
     */
    public Optional<EditorDescriptor> resolveDefault(String configuredDefault) {
        if (configuredDefault != null && !configuredDefault.isBlank()) {
            Optional<EditorDescriptor> configured = byId(configuredDefault);
            if (configured.isPresent() && resolveExecutable(configured.get()).isPresent()) {
                return configured;
            }
        }
        return firstAvailable();
    }

    /** @return el primer editor del catálogo que esté instalado */
    public Optional<EditorDescriptor> firstAvailable() {
        return EDITORS.stream()
                .filter(editor -> resolveExecutable(editor).isPresent())
                .findFirst();
    }
}
