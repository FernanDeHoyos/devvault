package com.devvault.discovery.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.devvault.discovery.application.dto.ProjectTreeNode;

class ProjectStructureServiceTest {

    @TempDir
    Path projectRoot;

    private final ProjectStructureService service = new ProjectStructureService();

    @Test
    void returnsSortedProjectTreeAndSkipsGeneratedDirectories() throws Exception {
        Files.createDirectories(projectRoot.resolve("src/main/java"));
        Files.createDirectories(projectRoot.resolve("node_modules/example"));
        Files.writeString(projectRoot.resolve("README.md"), "# project");
        Files.writeString(projectRoot.resolve("src/main/java/App.java"), "class App {}");

        ProjectTreeNode tree = service.scan(projectRoot);

        assertEquals(projectRoot.getFileName().toString(), tree.name());
        assertEquals("", tree.path());
        assertEquals(2, tree.children().size());
        assertEquals("src", tree.children().get(0).name());
        assertEquals("README.md", tree.children().get(1).name());
        assertTrue(tree.children().get(0).directory());
        assertEquals("src/main/java/App.java", findFile(tree, "App.java").path());
    }

    private ProjectTreeNode findFile(ProjectTreeNode node, String name) {
        if (node.name().equals(name)) {
            return node;
        }
        return node.children().stream()
                .map(child -> findFile(child, name))
                .filter(found -> found != null)
                .findFirst()
                .orElse(null);
    }
}
