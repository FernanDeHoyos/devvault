package com.devvault.discovery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import com.devvault.discovery.plugin.ReactNodePlugin;
import com.devvault.discovery.plugin.ProjectArchitecturePlugin;
import com.devvault.discovery.plugin.SourceLanguagesPlugin;
import com.devvault.plugin.domain.PluginDescriptor;
import com.devvault.plugin.infrastructure.PluginDescriptorRepository;

class ScannerEngineTest {

    @Test
    void mergesEvidenceFromEnabledPluginsAndPrunesGeneratedDirectories(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
                {"dependencies":{"react":"^19.0.0"}}
                """);
        Files.writeString(tempDir.resolve("index.html"), "<main></main>");
        Path styles = Files.createDirectories(tempDir.resolve("src")).resolve("styles.css");
        Files.writeString(styles, "body { color: black; }");
        Path generatedHtml = Files.createDirectories(tempDir.resolve("dist")).resolve("generated.html");
        Files.writeString(generatedHtml, "<main></main>");
        Path dependencyHtml = Files.createDirectories(tempDir.resolve("node_modules/example"))
                .resolve("generated.html");
        Files.writeString(dependencyHtml, "<main></main>");

        PluginDescriptorRepository repository = Mockito.mock(PluginDescriptorRepository.class);
        when(repository.findByEnabledTrue()).thenReturn(List.of(
                new PluginDescriptor("react-node-detector", "package.json", "1.0"),
                new PluginDescriptor("project-architecture-detector", "package.json", "1.0"),
                new PluginDescriptor("source-language-detector", "*.html", "1.0")));
        ScannerEngine engine = new ScannerEngine(
                List.of(new ReactNodePlugin(), new ProjectArchitecturePlugin(), new SourceLanguagesPlugin()),
                repository);

        List<ScannerEngine.ScannedProject> projects = engine.scan(tempDir);

        assertEquals(1, projects.size());
        Map<String, Object> markers = projects.get(0).detectionResult().rawMarkers();
        List<?> findings = (List<?>) markers.get("stackFindings");
        assertTrue(findings.stream().anyMatch(finding -> finding.toString().contains("React")));
        assertTrue(findings.stream().anyMatch(finding -> finding.toString().contains("HTML")));
        assertTrue(findings.stream().anyMatch(finding -> finding.toString().contains("CSS")));
        assertEquals(3, ((Map<?, ?>) markers.get("pluginDetections")).size());
        assertTrue(((List<?>) markers.get("dependencies")).stream()
                .anyMatch(dependency -> dependency.toString().contains("react")));
    }
}
