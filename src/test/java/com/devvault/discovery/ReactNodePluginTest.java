package com.devvault.discovery;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.Test;
import org.junit.jupiter.api.io.TempDir;

import com.devvault.discovery.plugin.DetectionResult;
import com.devvault.discovery.plugin.ReactNodePlugin;

public class ReactNodePluginTest {

    private final ReactNodePlugin plugin = new ReactNodePlugin(); 

    /**
     * Prueba que el plugin detecta correctamente un proyecto React con la versión especificada en el package.json.
     * @param tempDir un directorio temporal proporcionado por JUnit para la prueba
     * @throws IOException si ocurre un error al escribir el archivo package.json
     */
    @Test
    void shouldDetectReactWithVersion(@TempDir Path tempDir) throws IOException {
        // Crea un archivo package.json con contenido de ejemplo
        writePackageJson(tempDir, """
                {
                    "name": "my-react-app",
                    "version": "1.0.0",
                    "dependencies": {
                        "react": "^18.2.0",
                        "react-dom": "^18.2.0"
                    }
                }
                """);

        Optional<DetectionResult> result = plugin.detect(tempDir);
        // Verifica que se detectó correctamente React con la versión esperada
        assertTrue(result.isPresent());
        assertEquals("JavaScript", result.get().language());
        assertEquals("React", result.get().framework());
        assertEquals("18.2.0", result.get().version());
        
    }

    /**
     * Prueba que el plugin detecta correctamente un proyecto TypeScript cuando
     * hay un archivo tsconfig.json presente.
     * @param tempDir un directorio temporal proporcionado por JUnit para la prueba
     * @throws IOException si ocurre un error al escribir el archivo tsconfig.json
     */
    @Test
    void shouldDetectTypeScriptWhenTsconfigIsPresent(@TempDir Path tempDir) throws IOException {
        // Crea un archivo package.json con contenido de ejemplo
        writePackageJson(tempDir, """
                {
                    "name": "my-react-app",
                    "version": "1.0.0",
                    "dependencies": {
                        "react": "^18.2.0",
                        "react-dom": "^18.2.0"
                    }
                }
                """);
        // Crea un archivo tsconfig.json para simular un proyecto TypeScript
        Files.writeString(tempDir.resolve("tsconfig.json"), "{}");

        Optional<DetectionResult> result = plugin.detect(tempDir);
        // Verifica que se detectó correctamente TypeScript
        assertTrue(result.isPresent());
        assertEquals("TypeScript", result.get().language());
    }

    @Test
    void shouldReturnEmptyIfNoPackageJson(@TempDir Path tempDir) {
        Optional<DetectionResult> result = plugin.detect(tempDir);
        // Verifica que no se detecta ningún proyecto si no hay package.json
        assertTrue(result.isEmpty());
    }

    private void writePackageJson(Path dir, String content) throws IOException {
        Files.writeString(dir.resolve("package.json"), content);
    }
    
}
