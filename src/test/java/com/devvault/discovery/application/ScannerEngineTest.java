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
import com.devvault.discovery.plugin.TechnologyPluginCatalog;
import com.devvault.plugin.domain.PluginDescriptor;
import com.devvault.plugin.infrastructure.PluginDescriptorRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

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
                new TechnologyPluginCatalog(
                        List.of(new ReactNodePlugin(), new ProjectArchitecturePlugin(), new SourceLanguagesPlugin()),
                        new ObjectMapper(), tempDir.resolve("extensions")),
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

    @Test
    void scansWithInstalledExtensionOnlyWhenEnabled(@TempDir Path tempDir) throws Exception {
        Path extensions = Files.createDirectory(tempDir.resolve("extensions"));
        Files.writeString(extensions.resolve("00-invalid.json"), """
                {"schemaVersion":1,"id":"community.go","match":{"allFiles":["../go.mod"]},"result":{"language":"Wrong"}}
                """);
        Files.writeString(extensions.resolve("01-go.json"), """
                {"schemaVersion":1,"id":"community.go","match":{"allFiles":["go.mod","main.go"]},
                 "result":{"language":"Go","technologies":[{"name":"Go Modules","category":"TOOLS"}]}}
                """);
        Files.writeString(extensions.resolve("02-duplicate.json"), """
                {"schemaVersion":1,"id":"community.go","match":{"allFiles":["go.mod"]},"result":{"language":"Wrong"}}
                """);
        Path workspace = Files.createDirectory(tempDir.resolve("workspace"));
        Path project = Files.createDirectory(workspace.resolve("go-app"));
        Files.createFile(project.resolve("go.mod"));
        Files.writeString(project.resolve("main.go"), "os.Getenv(\"APP_PORT\")");
        Files.writeString(project.resolve(".env"), "APP_PORT=secret-test-value\nexport API_TOKEN=another-secret\n");
        Files.writeString(project.resolve("application.yml"), "port: ${HTTP_PORT:private-default}\n");
        Files.createFile(workspace.resolve("go.mod"));

        TechnologyPluginCatalog catalog = new TechnologyPluginCatalog(List.of(), new ObjectMapper(), extensions);
        assertEquals(1, catalog.detectors().size());
        PluginDescriptorRepository repository = Mockito.mock(PluginDescriptorRepository.class);
        when(repository.findByEnabledTrue()).thenReturn(
                List.of(new PluginDescriptor("community.go", "go.mod, main.go", "1.0")), List.of());
        ScannerEngine engine = new ScannerEngine(catalog, repository);

        var projects = engine.scan(workspace);
        assertEquals(1, projects.size());
        assertEquals(project, projects.get(0).path());
        assertEquals("Go", projects.get(0).detectionResult().language());
        assertEquals(Map.of("APP_PORT", List.of(".env", "main.go"),
                        "API_TOKEN", List.of(".env"), "HTTP_PORT", List.of("application.yml")),
                projects.get(0).detectionResult().rawMarkers().get("environmentVariables"));
        List<?> stack = (List<?>) projects.get(0).detectionResult().rawMarkers().get("stackFindings");
        assertEquals(2, stack.size());
        assertEquals("LANGUAGE", ((Map<?, ?>) stack.get(0)).get("category"));
        Map<?, ?> tool = (Map<?, ?>) stack.get(1);
        assertEquals("Go Modules", tool.get("name"));
        assertEquals("TOOLS", tool.get("category"));
        assertTrue(((List<?>) tool.get("evidence")).contains(
                Map.of("file", "go.mod", "property", "match.allFiles", "value", "go.mod")));
        assertTrue(engine.scan(workspace).isEmpty());
    }
}
