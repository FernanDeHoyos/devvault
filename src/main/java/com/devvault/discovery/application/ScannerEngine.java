package com.devvault.discovery.application;

import com.devvault.discovery.plugin.DetectionResult;
import com.devvault.discovery.plugin.TechnologyPlugin;
import com.devvault.plugin.infrastructure.PluginDescriptorRepository;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/**
 * Recorre recursivamente un Workspace, poda carpetas técnicas irrelevantes
 * (RF-07, RNF-02) y delega la detección de tecnología a cada TechnologyPlugin
 * registrado en el contexto de Spring que además esté HABILITADO en
 * plugin_descriptors (Plugin System, v0.3).
 */
@Component
public class ScannerEngine {
    
    // Poda carpetas irrelevantes para la detección de proyectos (RF-07, RNF-02)
    private static final Set<String> IGNORED_DIRS = Set.of(
        ".git", 
        "node_modules", 
        "target", 
        "build", 
        "out", 
        ".idea", 
        ".vscode", 
        ".dist",
        ".gradle",
        ".mvn",
        "venv",
        "__pycache__",
        ".pytest_cache",
        ".next",
        ".nuxt",
        "vendor");

    // Inyección de dependencias de los plugins de tecnología
    private final List<TechnologyPlugin> plugins;
    private final PluginDescriptorRepository pluginDescriptorRepository;


    public ScannerEngine(List<TechnologyPlugin> plugins, PluginDescriptorRepository pluginDescriptorRepository) {
        this.plugins = plugins;
        this.pluginDescriptorRepository = pluginDescriptorRepository;
    }

    /**
     * Escanea recursivamente un directorio raíz, detectando proyectos según los plugins de tecnología registrados.
     * Poda carpetas irrelevantes y delega la detección a cada plugin.
     * @param rootPath el directorio raíz del workspace a escanear
     * @return una lista de ScannedProject que representan los proyectos detectados
     * @throws RuntimeException si ocurre un error durante el escaneo
     */
    public List<ScannedProject> scan(Path root) {
        Set<String> enabledPluginNames = pluginDescriptorRepository.findByEnabledTrue().stream()
                .map(pd -> pd.getName())
                .collect(Collectors.toSet());
 
        List<TechnologyPlugin> activePlugins = plugins.stream()
                .filter(p -> enabledPluginNames.contains(p.pluginName()))
                .sorted(java.util.Comparator.comparingInt(TechnologyPlugin::detectionPriority).reversed())
                .toList();
 
        List<ScannedProject> results = new ArrayList<>();
 
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (IGNORED_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    detectInDirectory(dir, activePlugins).ifPresent(results::add);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new RuntimeException("Error recorriendo el Workspace: " + root, e);
        }
 
        return results;
    }

    /**
     * Intenta detectar un proyecto en un directorio dado utilizando 
     * los plugins de tecnología registrados.
     * @param dir el directorio a escanear
     * @return un Optional que contiene un ScannedProject si se detecta 
     * un proyecto, o vacío si no se detecta ninguno
     */
    private Optional<ScannedProject> detectInDirectory(Path dir, List<TechnologyPlugin> activePlugins) {
        for (TechnologyPlugin plugin : activePlugins) {
            Optional<DetectionResult> result = plugin.detect(dir);
            if (result.isPresent()) {
                return Optional.of(new ScannedProject(dir, result.get()));
            }
        }
        return Optional.empty();
    }

    /**
     * Representa un proyecto escaneado, incluyendo su ruta y el resultado de la detección.
     */
    public record ScannedProject(Path path, DetectionResult detectionResult) {}
}
