package com.devvault.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.devvault.discovery.plugin.ProjectArchitecturePlugin;

class ProjectArchitecturePluginTest {

    private final ProjectArchitecturePlugin plugin = new ProjectArchitecturePlugin();

    @Test
    void discoversBuildModulesDependenciesDatabaseAndComposeToolsWithoutKeepingCredentials(
            @TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pom.xml"), """
                <project>
                  <modules><module>backend</module></modules>
                  <dependencies>
                    <dependency>
                      <groupId>org.postgresql</groupId>
                      <artifactId>postgresql</artifactId>
                      <version>42.7.4</version>
                    </dependency>
                  </dependencies>
                  <dependencyManagement>
                    <dependencies>
                      <dependency><groupId>example</groupId><artifactId>managed-only</artifactId></dependency>
                    </dependencies>
                  </dependencyManagement>
                </project>
                """);
        Path backend = Files.createDirectories(tempDir.resolve("backend"));
        Files.writeString(backend.resolve("pom.xml"), "<project></project>");
        Path composeDirectory = Files.createDirectories(tempDir.resolve("ops"));
        Files.writeString(composeDirectory.resolve("compose.yml"), """
                services:
                  db:
                    image: user:service-secret@registry.example/postgres:16
                    environment:
                      POSTGRES_PASSWORD: super-secret-value
                """);
        Path resources = Files.createDirectories(backend.resolve("src/main/resources"));
                Files.writeString(resources.resolve("application.yml"), """
                        spring:
                          datasource:
                            url: jdbc:postgresql://admin:another-secret@localhost/sample
                            password: another-secret
                        """);

        Map<String, Object> markers = plugin.detect(tempDir).orElseThrow().rawMarkers();
        List<?> modules = (List<?>) markers.get("architectureModules");
        List<?> dependencies = (List<?>) markers.get("dependencies");
        List<?> databases = (List<?>) markers.get("databases");
        List<?> tools = (List<?>) markers.get("tools");
        String serialized = markers.toString();

        assertTrue(modules.stream().anyMatch(value -> value.toString().contains("MAVEN_MODULE")
                && value.toString().contains("backend")));
        assertTrue(dependencies.stream().anyMatch(value -> value.toString().contains("org.postgresql:postgresql")
                && value.toString().contains("42.7.4")));
        assertFalse(dependencies.stream().anyMatch(value -> value.toString().contains("managed-only")));
        assertTrue(databases.stream().anyMatch(value -> value.toString().contains("PostgreSQL")
                && value.toString().contains("CONFIGURED")));
        assertTrue(databases.stream().anyMatch(value -> value.toString().contains("DECLARED")));
        assertTrue(tools.stream().anyMatch(value -> value.toString().contains("Maven")));
        assertTrue(tools.stream().anyMatch(value -> value.toString().contains("Docker Compose")));
        assertFalse(serialized.contains("super-secret-value"));
        assertFalse(serialized.contains("service-secret"));
        assertFalse(serialized.contains("another-secret"));
        assertFalse(serialized.contains("admin:"));
    }

    @Test
    void onlyReadsDeclaredPythonDependencySections(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("pyproject.toml"), """
                [project]
                name = "sample"
                version = "1.2.3"
                requires-python = ">=3.11"
                dependencies = [
                  "fastapi>=0.110",
                  "uvicorn[standard]==0.29.0"
                ]

                [tool.ruff]
                target-version = "py311"

                [tool.poetry.group.dev.dependencies]
                pytest = "^8.0"
                """);

        List<?> dependencies = (List<?>) plugin.detect(tempDir).orElseThrow().rawMarkers().get("dependencies");

        assertTrue(dependencies.stream().anyMatch(value -> value.toString().contains("fastapi")
                && value.toString().contains("PRODUCTION")));
        assertTrue(dependencies.stream().anyMatch(value -> value.toString().contains("pytest")
                && value.toString().contains("DEVELOPMENT")));
        assertFalse(dependencies.stream().anyMatch(value -> value.toString().contains("target-version")
                || value.toString().contains("requires-python")
                || value.toString().contains("sample")));
    }

    @Test
    void readsGradleDeclaredModulesDependenciesAndWrapper(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("build.gradle"), """
                include ':api', ':worker'
                dependencies {
                    implementation 'org.springframework:spring-web:6.2.0'
                    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
                }
                """);
        Path wrapper = Files.createDirectories(tempDir.resolve("gradle/wrapper"));
        Files.writeString(wrapper.resolve("gradle-wrapper.properties"),
                "distributionUrl=https\\\\://services.gradle.org/distributions/gradle-8.10-bin.zip\n");

        Map<String, Object> markers = plugin.detect(tempDir).orElseThrow().rawMarkers();
        List<?> modules = (List<?>) markers.get("architectureModules");
        List<?> dependencies = (List<?>) markers.get("dependencies");
        List<?> tools = (List<?>) markers.get("tools");

        assertTrue(modules.stream().anyMatch(value -> value.toString().contains("api")));
        assertTrue(modules.stream().anyMatch(value -> value.toString().contains("worker")));
        assertTrue(dependencies.stream().anyMatch(value -> value.toString().contains("spring-web")
                && value.toString().contains("6.2.0") && value.toString().contains("PRODUCTION")));
        assertTrue(dependencies.stream().anyMatch(value -> value.toString().contains("junit-jupiter")
                && value.toString().contains("DEVELOPMENT")));
        assertTrue(tools.stream().anyMatch(value -> value.toString().contains("Gradle Wrapper")
                && value.toString().contains("8.10")));
    }

    @Test
    void distinguishesComposerDirectAndTransitivePackages(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("composer.json"), """
                {
                  "require": {"monolog/monolog": "^3.0"},
                  "require-dev": {"phpunit/phpunit": "^11.0"}
                }
                """);
        Files.writeString(tempDir.resolve("composer.lock"), """
                {
                  "packages": [
                    {"name": "monolog/monolog", "version": "3.8.0"},
                    {"name": "psr/log", "version": "3.0.2"}
                  ],
                  "packages-dev": [
                    {"name": "phpunit/phpunit", "version": "11.5.0"},
                    {"name": "sebastian/diff", "version": "6.0.2"}
                  ]
                }
                """);

        List<?> dependencies = (List<?>) plugin.detect(tempDir).orElseThrow().rawMarkers().get("dependencies");

        assertTrue(dependencies.stream().anyMatch(value -> value.toString().contains("monolog/monolog")
                && value.toString().contains("PRODUCTION") && value.toString().contains("3.8.0")));
        assertTrue(dependencies.stream().anyMatch(value -> value.toString().contains("phpunit/phpunit")
                && value.toString().contains("DEVELOPMENT") && value.toString().contains("11.5.0")));
        assertTrue(dependencies.stream().anyMatch(value -> value.toString().contains("psr/log")
                && value.toString().contains("TRANSITIVE")));
        assertTrue(dependencies.stream().anyMatch(value -> value.toString().contains("sebastian/diff")
                && value.toString().contains("TRANSITIVE")));
    }

    @Test
    void readsProductionAndDevelopmentDependenciesFromNodeManifestAndLockfile(@TempDir Path tempDir)
            throws IOException {
        Files.writeString(tempDir.resolve("package.json"), """
                {
                  "dependencies": {"react": "^19.0.0"},
                  "devDependencies": {"vite": "^6.0.0"}
                }
                """);
        Files.writeString(tempDir.resolve("package-lock.json"), """
                {
                  "lockfileVersion": 3,
                  "packages": {
                    "": {"dependencies": {"react": "^19.0.0"}},
                    "node_modules/react": {"version": "19.0.0"},
                    "node_modules/loose-envify": {"version": "1.4.0", "dev": false},
                    "node_modules/vite": {"version": "6.0.0", "dev": true}
                  }
                }
                """);

        Map<String, Object> markers = plugin.detect(tempDir).orElseThrow().rawMarkers();
        List<?> dependencies = (List<?>) markers.get("dependencies");

        assertTrue(dependencies.stream().anyMatch(value -> value.toString().contains("react")
                && value.toString().contains("PRODUCTION")));
        assertTrue(dependencies.stream().anyMatch(value -> value.toString().contains("vite")
                && value.toString().contains("DEVELOPMENT")));
        assertTrue(dependencies.stream().anyMatch(value -> value.toString().contains("loose-envify")
                && value.toString().contains("TRANSITIVE")));
    }

    @Test
    void doesNotRegisterAWorkspaceContainerAsAProjectWithoutRootManifests(@TempDir Path tempDir)
            throws IOException {
        Path child = Files.createDirectories(tempDir.resolve("child-project"));
        Files.writeString(child.resolve("package.json"), "{\"dependencies\":{\"react\":\"^19.0.0\"}}");

        assertTrue(plugin.detect(tempDir).isEmpty());
    }
}
