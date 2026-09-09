package com.devvault.discovery.plugin;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/// Implementación de TechnologyPlugin para detectar proyectos Spring Boot.
@Component
public class SpringBootPlugin implements TechnologyPlugin {
   
    // Expresión regular para detectar la versión de Spring Boot en el archivo pom.xml
    private static final Pattern SPRING_BOOT_VERSION = Pattern.compile("<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>(.*?)</version>",
            Pattern.DOTALL);   


    /**
     * Intenta detectar un proyecto Spring Boot en la carpeta dada.
     * Si encuentra un archivo pom.xml y detecta que es un proyecto Spring Boot,
     * devuelve un DetectionResult con la información del proyecto.
     * @param projectDir la carpeta del proyecto a analizar
     * @return un Optional con el DetectionResult si se detecta Spring Boot, o vacío si no
     */
    @Override
    public Optional<DetectionResult> detect(Path projectDir) {
        Path pomFile = projectDir.resolve("pom.xml");
        if (!pomFile.toFile().exists()) {
            return Optional.empty();
        }

        // Leer el contenido del archivo pom.xml
        String content;
        try {
            content = java.nio.file.Files.readString(pomFile);
        } catch (java.io.IOException e) {
            return Optional.empty();
        }

        // Verificar si el contenido del pom.xml contiene la dependencia de Spring Boot
        boolean isSpringBoot = SPRING_BOOT_VERSION.matcher(content).find();
        String framework = isSpringBoot ? "Spring Boot" : "Java";
        String version = isSpringBoot ? extractVersion(content) : null;

        // Devolver un DetectionResult con la información del proyecto
        return Optional.of(new DetectionResult(
            "Java", 
            framework, 
            version,
            Map.of("marker", "pom.xml", "sppringBootDetected", isSpringBoot)));
        }

        /**
         * Extrae la versión de Spring Boot del contenido del archivo pom.xml.
         * @param content el contenido del archivo pom.xml
         * @return la versión de Spring Boot si se encuentra, o "desconocida" si no
         */
        private String extractVersion(String content) {
            var matcher = SPRING_BOOT_VERSION.matcher(content);
             return matcher.find() ? matcher.group(1).trim() : "desconocida";
    }
}
