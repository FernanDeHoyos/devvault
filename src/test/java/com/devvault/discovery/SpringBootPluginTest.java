package com.devvault.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.Test;
import org.junit.jupiter.api.io.TempDir;

import com.devvault.discovery.plugin.DetectionResult;
import com.devvault.discovery.plugin.SpringBootPlugin;

class SpringBootPluginTest  {
   
    private final SpringBootPlugin plugin = new SpringBootPlugin();

    /**
     * Prueba que el plugin detecta correctamente un proyecto Spring Boot
     * con la versión especificada en el pom.xml.
     * @param tempDir un directorio temporal proporcionado por JUnit para la prueba
     * @throws IOException si ocurre un error al escribir el archivo pom.xml
     */
    @Test 
    void shouldDetectSpringBootWithVersion(@TempDir Path tempDir) throws IOException {
        // Crea un archivo pom.xml con contenido de ejemplo
        writePom(tempDir, """
             <project>
                    <parent>
                        <groupId>org.springframework.boot</groupId>
                        <artifactId>spring-boot-starter-parent</artifactId>
                        <version>3.3.2</version>
                    </parent>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web</artifactId>
                        </dependency>
                    </dependencies>
                </project>
        """);

        Optional<DetectionResult> result = plugin.detect(tempDir);
        // Verifica que se detectó correctamente Spring Boot con la versión esperada
        assertTrue(result.isPresent());
        assertEquals("Java", result.get().language());
        assertEquals("Spring Boot", result.get().framework());
        assertEquals("3.3.2", result.get().version());

    }

    /**
     * Prueba que el plugin detecta correctamente un proyecto Java genérico
     * sin la dependencia de Spring Boot en el pom.xml.
     * @param tempDir un directorio temporal proporcionado por JUnit para la prueba
     * @throws IOException si ocurre un error al escribir el archivo pom.xml
     */
    @Test
    void shouldDetectGenericJavaWithoutSpringBoot(@TempDir Path tempDir) throws IOException {
        // Crea un archivo pom.xml sin la dependencia de Spring Boot
        writePom(tempDir, """
             <project>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.commons</groupId>
                            <artifactId>commons-lang3</artifactId>
                            <version>3.12.0</version>
                        </dependency>
                    </dependencies>
                </project>
        """);

        Optional<DetectionResult> result = plugin.detect(tempDir);
        // Verifica que se detectó correctamente Java genérico sin Spring Boot
        assertTrue(result.isPresent());
        assertEquals("Java", result.get().language());
        assertEquals("Java", result.get().framework());
        assertEquals(null, result.get().version());
    }

    /**
     * Prueba que el plugin devuelve vacío si no hay un archivo pom.xml en el directorio.
     * @param tempDir un directorio temporal proporcionado por JUnit para la prueba
     */
    @Test
    void shouldReturnEmptyIfNoPomXml(@TempDir Path tempDir) {
        Optional<DetectionResult> result = plugin.detect(tempDir);
        // Verifica que no se detecta ningún proyecto si no hay pom.xml
        assertTrue(result.isEmpty());
    }

    private void writePom(Path dir, String content) throws IOException {
        Files.writeString(dir.resolve("pom.xml"), content);
    }
}
