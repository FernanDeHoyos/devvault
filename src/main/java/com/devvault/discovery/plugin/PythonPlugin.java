package com.devvault.discovery.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** Detects Python projects and directly declared Django dependencies without executing project files. */
@Component
public class PythonPlugin implements TechnologyPlugin {

    private static final Pattern DJANGO_REQUIREMENT = Pattern.compile(
            "(?im)^\\s*django\\s*(?:\\[.*?\\])?\\s*(?:([<>=!~]+)\\s*([^;\\s]+))?");
    private static final Pattern DJANGO_PYPROJECT = Pattern.compile(
            "(?i)[\"']django[\"']\\s*=\\s*[\"']([^\"']+)[\"']");

    @Override
    public String pluginName() {
        return "python-project-detector";
    }

    @Override
    public String targetMarkerFiles() {
        return "pyproject.toml, requirements.txt, Pipfile, *.py";
    }

    @Override
    public int detectionPriority() {
        return 15;
    }

    @Override
    public Optional<DetectionResult> detect(Path projectDir) {
        Path pyproject = projectDir.resolve("pyproject.toml");
        Path requirements = projectDir.resolve("requirements.txt");
        Path pipfile = projectDir.resolve("Pipfile");
        Path pythonSource = directPythonSource(projectDir);
        if (!Files.isRegularFile(pyproject) && !Files.isRegularFile(requirements)
                && !Files.isRegularFile(pipfile) && pythonSource == null) {
            return Optional.empty();
        }

        String djangoVersion = null;
        String djangoEvidence = null;
        List<String> warnings = new ArrayList<>();
        if (Files.isRegularFile(requirements)) {
            String requirement = read(requirements, warnings);
            Matcher matcher = DJANGO_REQUIREMENT.matcher(requirement);
            if (matcher.find()) {
                djangoEvidence = "requirements.txt";
                djangoVersion = matcher.group(2);
            }
        }
        if (djangoEvidence == null && Files.isRegularFile(pyproject)) {
            Matcher matcher = DJANGO_PYPROJECT.matcher(read(pyproject, warnings));
            if (matcher.find()) {
                djangoEvidence = "pyproject.toml";
                djangoVersion = matcher.group(1);
            }
        }

        List<Map<String, Object>> findings = new ArrayList<>();
        String languageEvidence = Files.isRegularFile(pyproject) ? "pyproject.toml"
                : Files.isRegularFile(requirements) ? "requirements.txt"
                        : Files.isRegularFile(pipfile) ? "Pipfile" : projectDir.relativize(pythonSource).toString();
        findings.add(TechnologyEvidence.direct(
                "Python", "LANGUAGE", null, languageEvidence, "Python project marker", languageEvidence, true));
        if (djangoEvidence != null) {
            findings.add(TechnologyEvidence.direct(
                    "Django", "BACKEND", djangoVersion, djangoEvidence, "django dependency",
                    djangoVersion, true));
        }
        if (Files.isRegularFile(pyproject)) {
            findings.add(TechnologyEvidence.direct(
                    "Poetry / pyproject", "TOOLS", null, "pyproject.toml", "project/build metadata",
                    "pyproject.toml", true));
        } else if (Files.isRegularFile(pipfile)) {
            findings.add(TechnologyEvidence.direct(
                    "Pipenv", "TOOLS", null, "Pipfile", "dependency manifest", "Pipfile", true));
        } else if (Files.isRegularFile(requirements)) {
            findings.add(TechnologyEvidence.direct(
                    "pip", "TOOLS", null, "requirements.txt", "dependency manifest",
                    "requirements.txt", true));
        }

        Map<String, Object> markers = new LinkedHashMap<>();
        markers.put("marker", languageEvidence);
        markers.put("pythonDetected", true);
        markers.put("djangoDetected", djangoEvidence != null);
        markers.put("stackFindings", findings);
        if (!warnings.isEmpty()) {
            markers.put("warnings", warnings);
        }
        return Optional.of(new DetectionResult("Python", djangoEvidence == null ? "Python" : "Django",
                djangoVersion, markers));
    }

    private Path directPythonSource(Path projectDir) {
        try (var files = Files.list(projectDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".py"))
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    private String read(Path file, List<String> warnings) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            warnings.add("No se pudo leer " + file.getFileName() + ".");
            return "";
        }
    }
}
