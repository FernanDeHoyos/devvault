package com.devvault.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.devvault.discovery.application.ProjectIntelligenceService;
import com.devvault.discovery.application.dto.ProjectIntelligenceResponse;
import com.devvault.discovery.domain.Project;
import com.devvault.discovery.domain.ProjectProfile;
import com.devvault.discovery.domain.ProjectStatus;
import com.devvault.discovery.plugin.TechnologyEvidence;

class ProjectIntelligenceServiceTest {

    private final ProjectIntelligenceService service = new ProjectIntelligenceService();

    @Test
    void buildsEvidenceBackedStackAndMapWithoutExposingUnrecognizedMarkers() {
        Map<String, Object> finding = TechnologyEvidence.direct(
                "Spring Boot", "BACKEND", "3.4.0", "pom.xml",
                "spring-boot-starter-parent", "3.4.0", true);
        Project project = new Project(
                UUID.randomUUID(), "sample", "C:\\projects\\sample",
                "Java", "Spring Boot", "3.4.0", ProjectStatus.ACTIVE);
        ProjectProfile profile = new ProjectProfile(
                UUID.randomUUID(),
                Map.of("stackFindings", List.of(finding), "password", "should-not-be-returned"));

        ProjectIntelligenceResponse result = service.describe(project, profile);

        assertEquals(1, result.stack().size());
        assertEquals("pom.xml", result.stack().get(0).evidence().get(0).file());
        assertEquals(2, result.map().nodes().size());
        assertEquals(1, result.map().edges().size());
        assertTrue(result.map().edges().get(0).evidence().get(0).value().equals("3.4.0"));
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void preservesLegacyProfilesAndShowsNoUnverifiedRelationship() {
        Project project = new Project(
                UUID.randomUUID(), "sample", "C:\\projects\\sample",
                "Java", "Java", null, ProjectStatus.ACTIVE);
        ProjectProfile profile = new ProjectProfile(UUID.randomUUID(), Map.of("marker", "pom.xml"));

        ProjectIntelligenceResponse result = service.describe(project, profile);

        assertEquals(1, result.stack().size());
        assertTrue(result.map().edges().stream().allMatch(edge -> edge.confidence().equals("DIRECT")));
    }

    @Test
    void exposesModulesDependenciesDatabasesAndToolsInTheArchitectureModel() {
        Project project = new Project(
                UUID.randomUUID(), "sample", "C:\\projects\\sample",
                "Java", "Spring Boot", "3.4.0", ProjectStatus.ACTIVE);
        Map<String, Object> module = Map.of(
                "name", "api",
                "path", "api",
                "type", "MAVEN_MODULE",
                "file", "pom.xml",
                "property", "modules");
        Map<String, Object> dependency = Map.of(
                "name", "org.postgresql:postgresql",
                "version", "42.7.4",
                "scope", "PRODUCTION",
                "manager", "Maven",
                "file", "api/pom.xml",
                "property", "dependency");
        Map<String, Object> database = Map.of(
                "name", "PostgreSQL",
                "status", "CONFIGURED",
                "file", "api/application.properties",
                "property", "spring.datasource.url");
        Map<String, Object> declaredDatabase = Map.of(
                "name", "PostgreSQL",
                "status", "DECLARED",
                "file", "compose.yml",
                "property", "services.db");
        Map<String, Object> tool = Map.of(
                "name", "Maven",
                "version", "3.9.9",
                "file", "pom.xml",
                "property", "build system");
        ProjectProfile profile = new ProjectProfile(UUID.randomUUID(), Map.of(
                "stackFindings", List.of(),
                "architectureModules", List.of(module),
                "dependencies", List.of(dependency),
                "databases", List.of(database, declaredDatabase),
                "tools", List.of(tool)));

        ProjectIntelligenceResponse result = service.describe(project, profile);

        assertEquals(1, result.modules().size());
        assertEquals("api", result.modules().get(0).name());
        assertEquals("42.7.4", result.dependencies().get(0).version());
        assertEquals(2, result.databases().size());
        assertTrue(result.databases().stream().anyMatch(value -> value.status().equals("CONFIGURED")));
        assertTrue(result.databases().stream().anyMatch(value -> value.status().equals("DECLARED")));
        assertTrue(result.tools().stream().anyMatch(value -> value.name().equals("Maven")));
        assertTrue(result.map().nodes().stream().anyMatch(node -> node.type().equals("MODULE")));
        assertTrue(result.map().nodes().stream().anyMatch(node -> node.type().equals("DATABASE")));
    }
}
