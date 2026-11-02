package com.devvault.discovery.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Component;

/** Detects directly present HTML, CSS, and SQL source files. */
@Component
public class SourceLanguagesPlugin implements TechnologyPlugin {

    @Override
    public String pluginName() {
        return "source-language-detector";
    }

    @Override
    public String targetMarkerFiles() {
        return "*.html, *.css, *.sql";
    }

    @Override
    public int detectionPriority() {
        return 1;
    }

    @Override
    public Optional<DetectionResult> detect(Path projectDir) {
        List<Map<String, Object>> findings = new ArrayList<>();
        addFinding(projectDir, "html", "HTML", "FRONTEND", findings);
        addFinding(projectDir, "css", "CSS", "FRONTEND", findings);
        addFinding(projectDir, "sql", "SQL", "DATABASE", findings);
        if (findings.isEmpty()) {
            return Optional.empty();
        }

        List<String> languages = findings.stream()
                .map(finding -> String.valueOf(finding.get("name")))
                .distinct()
                .toList();
        String primaryLanguage = languages.get(0);
        Map<String, Object> markers = new LinkedHashMap<>();
        markers.put("marker", "source files");
        markers.put("sourceLanguages", languages);
        markers.put("stackFindings", findings);
        return Optional.of(new DetectionResult(primaryLanguage, primaryLanguage, null, markers));
    }

    private void addFinding(Path projectDir, String extension, String name, String category,
            List<Map<String, Object>> findings) {
        Path sourceFile = directFile(projectDir, extension);
        if (sourceFile != null) {
            String relativePath = projectDir.relativize(sourceFile).toString().replace('\\', '/');
            findings.add(TechnologyEvidence.direct(
                    name, category, null, relativePath, "source file", relativePath, true));
        }
    }

    private Path directFile(Path projectDir, String extension) {
        try (var files = Files.list(projectDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith("." + extension))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
