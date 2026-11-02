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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
        "dist",
        ".gradle",
        ".mvn",
        "venv",
        ".venv",
        "__pycache__",
        ".pytest_cache",
        "coverage",
        ".cache",
        "generated",
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
                    detectInDirectory(dir, activePlugins).ifPresent(scanned -> {
                        int parentIndex = sourceOnly(scanned) ? containingProjectIndex(results, scanned.path()) : -1;
                        if (parentIndex < 0) {
                            results.add(scanned);
                        } else {
                            results.set(parentIndex, mergeSourceFindings(results.get(parentIndex), scanned));
                        }
                    });
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
        List<DetectionResult> detections = new ArrayList<>();
        Map<String, Object> pluginDetections = new LinkedHashMap<>();
        List<Object> stackFindings = new ArrayList<>();
        Set<String> technologies = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        Map<String, List<Object>> architectureCollections = new LinkedHashMap<>();
        for (String collection : List.of(
                "architectureModules", "dependencies", "tools", "databases", "configurations", "services")) {
            architectureCollections.put(collection, new ArrayList<>());
        }

        for (TechnologyPlugin plugin : activePlugins) {
            Optional<DetectionResult> result = plugin.detect(dir);
            if (result.isPresent()) {
                DetectionResult detection = result.get();
                detections.add(detection);
                pluginDetections.put(plugin.pluginName(), detection.rawMarkers());
                Object findings = detection.rawMarkers().get("stackFindings");
                if (findings instanceof List<?> values) {
                    stackFindings.addAll(values);
                }
                Object declaredTechnologies = detection.rawMarkers().get("technologies");
                if (declaredTechnologies instanceof List<?> values) {
                    values.stream().map(String::valueOf).forEach(technologies::add);
                }
                Object detectionWarnings = detection.rawMarkers().get("warnings");
                if (detectionWarnings instanceof List<?> values) {
                    values.stream().map(String::valueOf).forEach(warnings::add);
                }
                for (String collection : architectureCollections.keySet()) {
                    Object values = detection.rawMarkers().get(collection);
                    if (values instanceof List<?> items) {
                        architectureCollections.get(collection).addAll(items);
                    }
                }
            }
        }
        if (detections.isEmpty()) {
            return Optional.empty();
        }

        DetectionResult primary = detections.get(0);
        Map<String, Object> markers = new LinkedHashMap<>(primary.rawMarkers());
        markers.put("pluginDetections", pluginDetections);
        markers.put("stackFindings", stackFindings);
        markers.put("technologies", List.copyOf(technologies));
        architectureCollections.forEach((key, values) -> markers.put(key, List.copyOf(values)));
        if (!warnings.isEmpty()) {
            markers.put("warnings", warnings.stream().distinct().toList());
        }
        DetectionResult combined = new DetectionResult(
                primary.language(), primary.framework(), primary.version(), markers);
        return Optional.of(new ScannedProject(dir, combined));
    }

    private int containingProjectIndex(List<ScannedProject> projects, Path candidatePath) {
        int containingIndex = -1;
        int containingDepth = -1;
        for (int index = 0; index < projects.size(); index++) {
            Path projectPath = projects.get(index).path();
            if (candidatePath.startsWith(projectPath) && projectPath.getNameCount() > containingDepth) {
                containingIndex = index;
                containingDepth = projectPath.getNameCount();
            }
        }
        return containingIndex;
    }

    private boolean sourceOnly(ScannedProject project) {
        Object detections = project.detectionResult().rawMarkers().get("pluginDetections");
        return detections instanceof Map<?, ?> plugins
                && plugins.size() == 1
                && plugins.containsKey("source-language-detector");
    }

    private ScannedProject mergeSourceFindings(ScannedProject project, ScannedProject source) {
        DetectionResult existing = project.detectionResult();
        Map<String, Object> markers = new LinkedHashMap<>(existing.rawMarkers());
        Map<String, Object> pluginDetections = new LinkedHashMap<>();
        Object existingDetections = markers.get("pluginDetections");
        if (existingDetections instanceof Map<?, ?> values) {
            values.forEach((key, value) -> pluginDetections.put(String.valueOf(key), value));
        }
        Object sourceDetections = source.detectionResult().rawMarkers().get("pluginDetections");
        if (sourceDetections instanceof Map<?, ?> values) {
            values.forEach((key, value) -> pluginDetections.put(String.valueOf(key), value));
        }
        markers.put("pluginDetections", pluginDetections);

        List<Object> findings = new ArrayList<>();
        addList(markers.get("stackFindings"), findings);
        addList(source.detectionResult().rawMarkers().get("stackFindings"), findings);
        markers.put("stackFindings", findings);

        Set<String> technologies = new LinkedHashSet<>();
        addStringList(markers.get("technologies"), technologies);
        addStringList(source.detectionResult().rawMarkers().get("technologies"), technologies);
        markers.put("technologies", List.copyOf(technologies));

        List<String> warnings = new ArrayList<>();
        addStringList(markers.get("warnings"), warnings);
        addStringList(source.detectionResult().rawMarkers().get("warnings"), warnings);
        if (!warnings.isEmpty()) {
            markers.put("warnings", warnings.stream().distinct().toList());
        }

        return new ScannedProject(project.path(), new DetectionResult(
                existing.language(), existing.framework(), existing.version(), markers));
    }

    private void addList(Object source, List<Object> destination) {
        if (source instanceof List<?> values) {
            destination.addAll(values);
        }
    }

    private void addStringList(Object source, java.util.Collection<String> destination) {
        if (source instanceof List<?> values) {
            values.stream().map(String::valueOf).forEach(destination::add);
        }
    }

    /**
     * Representa un proyecto escaneado, incluyendo su ruta y el resultado de la detección.
     */
    public record ScannedProject(Path path, DetectionResult detectionResult) {}
}
