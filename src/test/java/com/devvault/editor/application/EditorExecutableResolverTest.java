package com.devvault.editor.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cubre la resolución del ejecutable de un editor, que es la parte con lógica
 * real del módulo: candidatos con variables de entorno, con comodines y con
 * extensiones de Windows.
 *
 * <p>No se prueba el arranque de un editor real porque eso abriría una ventana en
 * la máquina de quien ejecuta la suite. Lo que se prueba es exactamente la parte
 * que puede fallar en silencio.
 */
class EditorExecutableResolverTest {

    private final EditorExecutableResolver resolver = new EditorExecutableResolver();

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    @Test
    void shouldFindBareCommandInGivenPath(@TempDir Path tempDir) throws Exception {
        Path bin = Files.createDirectory(tempDir.resolve("bin"));
        Path code = Files.createFile(bin.resolve(isWindows() ? "code.cmd" : "code"));

        // El PATH se pasa como parámetro justamente para no depender de
        // manipular el entorno del proceso, que en los JDK modernos es inmutable.
        Optional<Path> resolved = resolver.findOnPath("code", bin.toString());

        assertTrue(resolved.isPresent(), "debería encontrar 'code' en el PATH");
        assertEquals(code.toAbsolutePath(), resolved.get());
    }

    @Test
    void shouldSearchEveryPathEntry(@TempDir Path tempDir) throws Exception {
        Path first = Files.createDirectory(tempDir.resolve("vacio"));
        Path second = Files.createDirectory(tempDir.resolve("con-binario"));
        Path subl = Files.createFile(second.resolve(isWindows() ? "subl.exe" : "subl"));

        String path = first + File.pathSeparator + second;
        Optional<Path> resolved = resolver.findOnPath("subl", path);

        assertTrue(resolved.isPresent());
        assertEquals(subl.toAbsolutePath(), resolved.get());
    }

    @Test
    void shouldReturnEmptyWhenPathIsNullOrBlank() {
        assertTrue(resolver.findOnPath("code", null).isEmpty());
        assertTrue(resolver.findOnPath("code", "   ").isEmpty());
    }

    @Test
    void shouldReturnEmptyForCommandAbsentFromPath(@TempDir Path tempDir) throws Exception {
        Files.createDirectory(tempDir.resolve("bin"));
        assertTrue(resolver.findOnPath("code", tempDir.resolve("bin").toString()).isEmpty());
    }

    @Test
    void shouldResolveAbsoluteExistingFile(@TempDir Path tempDir) throws Exception {
        Path exe = Files.createFile(tempDir.resolve("Code.exe"));
        Optional<Path> resolved = resolver.resolve(exe.toString());
        assertTrue(resolved.isPresent());
        assertEquals(exe.toAbsolutePath(), resolved.get());
    }

    @Test
    void shouldReturnEmptyForAbsoluteMissingFile(@TempDir Path tempDir) {
        assertTrue(resolver.resolve(tempDir.resolve("nope.exe").toString()).isEmpty());
    }

    @Test
    void shouldReturnEmptyForUnknownCommand() {
        assertTrue(resolver.resolve("editor-que-no-existe-xyz").isEmpty());
    }

    @Test
    void shouldExpandDefinedEnvironmentVariableAndThenFailOnMissingFile() {
        // %TEMP% existe: se expande y la ruta resultante no existe, así que el
        // fallo debe venir de que falta el archivo, no del % sin expandir.
        String temp = System.getenv("TEMP");
        org.junit.jupiter.api.Assumptions.assumeTrue(temp != null && !temp.isBlank(),
                "requiere la variable TEMP");
        assertTrue(resolver.resolve("%TEMP%" + File.separator + "no-existe-esta-carpeta").isEmpty());
    }

    @Test
    void shouldLeaveUndefinedVariablesUntouched() {
        // Una variable no definida no se sustituye, para que el diagnóstico
        // muestre la ruta tal como está escrita en el catálogo.
        assertTrue(resolver.resolve("%DEFINIDA_POR_NADIE_12345%\\code.cmd").isEmpty());
    }

    @Test
    void shouldResolveWildcardPickingNewestDirectory(@TempDir Path tempDir) throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("JetBrains"));

        Path oldDir = Files.createDirectory(root.resolve("IDEA 2023.1"));
        Files.createDirectories(oldDir.resolve("bin"));
        Path oldExe = Files.createFile(oldDir.resolve("bin").resolve("idea64.exe"));

        Path newDir = Files.createDirectory(root.resolve("IDEA 2024.2"));
        Files.createDirectories(newDir.resolve("bin"));
        Path newExe = Files.createFile(newDir.resolve("bin").resolve("idea64.exe"));

        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(newExe, FileTime.fromMillis(now));
        Files.setLastModifiedTime(oldExe, FileTime.fromMillis(now - 10_000_000L));

        // El patrón se arma como texto y no con Path.resolve porque '*' es un
        // carácter ilegal en una ruta de Windows.
        String pattern = root + File.separator + "IDEA *"
                + File.separator + "bin" + File.separator + "idea64.exe";
        Optional<Path> resolved = resolver.resolve(pattern);

        assertTrue(resolved.isPresent(), "debería resolver el patrón con comodín");
        assertEquals(newExe.toAbsolutePath(), resolved.get(), "debe ganar la carpeta más reciente");
    }

    @Test
    void shouldReturnEmptyWhenWildcardMatchesNothing(@TempDir Path tempDir) throws Exception {
        Path root = Files.createDirectory(tempDir.resolve("Vacio"));
        String pattern = root + File.separator + "No Existe *" + File.separator + "code.exe";
        assertTrue(resolver.resolve(pattern).isEmpty());
    }

    @Test
    void catalogShouldExposeUniqueIdsAndNonEmptyCandidates() {
        List<EditorDescriptor> editors = new EditorCatalog(resolver).all();

        assertFalse(editors.isEmpty());
        assertEquals(editors.size(),
                editors.stream().map(EditorDescriptor::id).distinct().count(),
                "los ids del catálogo deben ser únicos");
        editors.forEach(editor -> {
            assertFalse(editor.displayName().isBlank());
            assertFalse(editor.executables().isEmpty(),
                    editor.id() + " necesita al menos un candidato a ejecutable");
        });
    }

    @Test
    void catalogShouldLookUpByIdIgnoringCaseAndBlanks() {
        EditorCatalog catalog = new EditorCatalog(resolver);
        assertTrue(catalog.byId("VSCODE").isPresent());
        assertTrue(catalog.byId("  vscode  ").isPresent());
        assertTrue(catalog.byId(null).isEmpty());
        assertTrue(catalog.byId("   ").isEmpty());
        assertTrue(catalog.byId("no-existe").isEmpty());
    }

    @Test
    void catalogShouldNeverContainTerminalEditorsInThisPhase() {
        // Un editor de terminal abriría y moriría al instante sin consola.
        new EditorCatalog(resolver).all()
                .forEach(editor -> assertFalse(editor.isTerminalBased(),
                        editor.id() + " no debe ser un editor de terminal en esta fase"));
    }
}
