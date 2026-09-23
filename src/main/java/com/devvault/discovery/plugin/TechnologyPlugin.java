package com.devvault.discovery.plugin;

import java.nio.file.Path;
import java.util.Optional;


/**
 * Contrato que implementa cada detector de tecnología (RF-08, RF-24).
 * El ScannerEngine invoca detect() sobre cada carpeta candidata sin saber
 * nada de Spring Boot, React, ni ningún stack concreto.
 *
 * Los tres métodos con default (pluginName, targetMarkerFiles, version) son
 * los metadatos que el Plugin System usa para registrar cada plugin en
 * plugin_descriptors y permitir habilitarlo/deshabilitarlo vía API sin
 * tocar código — implementaciones existentes no necesitan sobreescribirlos
 * a menos que quieran un nombre o versión específicos.
 */
public interface TechnologyPlugin {
     /**
     * Intenta detectar esta tecnología en la carpeta dada.
     * Debe ser rápido y de solo lectura: mirar si existe un archivo marcador
     * (pom.xml, package.json, etc.) antes de abrir y parsear nada.
     */
    Optional<DetectionResult> detect(Path projectDir);

    /**
     * Obtiene la configuración necesaria para ejecutar localmente
     * el proyecto detectado.
     *
     * @param projectDir directorio raíz del proyecto
     * @return configuración de ejecución si esta tecnología es ejecutable
     */
    default Optional<RunConfiguration> getRunConfiguration(Path projectDir) {
        return Optional.empty();
    }

    default String pluginName() {
        return getClass().getSimpleName();
    }
 
    default String targetMarkerFiles() {
        return "-";
    }
 
    default String version() {
        return "1.0";
    }
}
