package com.devvault.discovery.plugin;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class TechnologyPluginCatalog {
    private static final Logger log = LoggerFactory.getLogger(TechnologyPluginCatalog.class);
    private static final long MAX_DEFINITION_BYTES = 64 * 1024;
    private static final Set<String> TECHNOLOGY_CATEGORIES =
            Set.of("FRONTEND", "BACKEND", "DATABASE", "INFRASTRUCTURE", "TOOLS", "LANGUAGE");
    private final Map<String, TechnologyPlugin> plugins;

    public TechnologyPluginCatalog(List<TechnologyPlugin> builtIns, ObjectMapper mapper,
            @Value("${devvault.extensions.directory}") Path directory) {
        Map<String, TechnologyPlugin> loaded = new LinkedHashMap<>();
        builtIns.forEach(plugin -> loaded.put(plugin.pluginName(), plugin));

        if (!Files.notExists(directory)) {
            try (var entries = Files.list(directory)) {
                for (Path file : entries.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                        .sorted().toList()) {
                    load(file, mapper, loaded);
                }
            } catch (IOException | UncheckedIOException e) {
                log.warn("No se pudo leer la carpeta de extensiones {}: {}", directory, e.getMessage());
            }
        }
        plugins = Collections.unmodifiableMap(loaded);
    }

    public List<TechnologyPlugin> detectors() {
        return List.copyOf(plugins.values());
    }

    public boolean contains(String id) {
        return plugins.containsKey(id);
    }

    private static void load(Path file, ObjectMapper mapper, Map<String, TechnologyPlugin> loaded) {
        try {
            if (Files.size(file) > MAX_DEFINITION_BYTES) {
                throw new IllegalArgumentException("el archivo supera 64 KiB");
            }
            Definition definition = mapper.readerFor(Definition.class)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                            DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readValue(file.toFile());
            DeclarativeDetector detector = validate(definition);
            if (loaded.putIfAbsent(detector.pluginName(), detector) != null) {
                throw new IllegalArgumentException("ID de plugin duplicado: " + detector.pluginName());
            }
        } catch (IOException | IllegalArgumentException e) {
            log.warn("Extensión omitida ({}): {}", file, e.getMessage());
        }
    }

    private static DeclarativeDetector validate(Definition definition) {
        if (definition == null || definition.schemaVersion() == null
                || !definition.schemaVersion().isIntegralNumber()
                || !definition.schemaVersion().canConvertToInt() || definition.schemaVersion().intValue() != 1) {
            throw new IllegalArgumentException("schemaVersion debe ser 1");
        }
        String id = definition.id();
        if (id == null || id.length() > 80 || !id.matches("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+")) {
            throw new IllegalArgumentException("id inválido; usa un nombre como community.go");
        }
        List<String> markers = definition.match() == null ? null : definition.match().allFiles();
        if (markers == null || markers.isEmpty() || markers.stream().anyMatch(marker ->
                marker == null || !marker.matches("[A-Za-z0-9_.-]{1,100}")
                        || marker.equals(".") || marker.equals(".."))
                || String.join(", ", markers).length() > 255) {
            throw new IllegalArgumentException("match.allFiles debe contener nombres de archivo simples");
        }
        Result result = definition.result();
        if (result == null || !validText(result.language())
                || (result.framework() != null && !validText(result.framework()))) {
            throw new IllegalArgumentException("result requiere language y admite framework (hasta 50 caracteres)");
        }
        List<StackTechnology> technologies = result.technologies() == null ? List.of() : result.technologies();
        if (technologies.stream().anyMatch(technology -> technology == null || !validText(technology.name())
                || technology.category() == null || !TECHNOLOGY_CATEGORIES.contains(technology.category()))) {
            throw new IllegalArgumentException("result.technologies requiere name y una category válida");
        }
        return new DeclarativeDetector(id, List.copyOf(markers), result.language(), result.framework(),
                List.copyOf(technologies));
    }

    private static boolean validText(String value) {
        return value != null && !value.isBlank() && value.length() <= 50;
    }

    private record Definition(JsonNode schemaVersion, String id, Match match, Result result) {}
    private record Match(List<String> allFiles) {}
    private record Result(String language, String framework, List<StackTechnology> technologies) {}
    private record StackTechnology(String name, String category) {}

    private record DeclarativeDetector(String pluginName, List<String> markers, String language,
            String framework, List<StackTechnology> technologies)
            implements TechnologyPlugin {
        @Override
        public String targetMarkerFiles() {
            return String.join(", ", markers);
        }

        @Override
        public int detectionPriority() {
            // Prefer built-in stack detectors, then file markers, then generic evidence.
            return 8;
        }

        @Override
        public Optional<DetectionResult> detect(Path projectDir) {
            if (!markers.stream().allMatch(marker ->
                    Files.isRegularFile(projectDir.resolve(marker), LinkOption.NOFOLLOW_LINKS))) {
                return Optional.empty();
            }
            List<Map<String, String>> evidence = markers.stream()
                    .map(marker -> Map.of("file", marker, "property", "match.allFiles", "value", marker))
                    .toList();
            List<Map<String, Object>> findings = new ArrayList<>();
            findings.add(stackFinding(language, "LANGUAGE", true, evidence));
            if (framework != null) {
                findings.add(stackFinding(framework, "BACKEND", true, evidence));
            }
            technologies.forEach(technology ->
                    findings.add(stackFinding(technology.name(), technology.category(), false, evidence)));
            return Optional.of(new DetectionResult(language, framework, null,
                    Map.of("technologies", findings.stream().map(finding -> finding.get("name")).distinct().toList(),
                            "matchedFiles", markers, "stackFindings", findings)));
        }

        private Map<String, Object> stackFinding(String name, String category, boolean primary,
                List<Map<String, String>> evidence) {
            return Map.of("name", name, "category", category, "primary", primary, "evidence", evidence);
        }
    }
}
