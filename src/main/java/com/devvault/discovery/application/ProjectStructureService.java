package com.devvault.discovery.application;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.devvault.discovery.application.dto.ProjectTreeNode;

/** Builds a bounded, read-only view of a project's filesystem structure. */
@Component
public class ProjectStructureService {

    private static final int MAX_DEPTH = 5;
    private static final int MAX_CHILDREN = 100;
    private static final int MAX_NODES = 2_500;
    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", "node_modules", "vendor", "target", "build", "dist", "out", ".gradle",
            ".mvn", ".idea", ".vscode", ".next", ".nuxt", ".cache", "coverage",
            "__pycache__", ".pytest_cache", ".venv", "venv", "generated", "bin", "obj");
    private static final Comparator<Path> ENTRY_ORDER = Comparator
            .comparing((Path path) -> !Files.isDirectory(path))
            .thenComparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER);

    public ProjectTreeNode scan(Path projectRoot) {
        Path root = projectRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No se encontró la carpeta del proyecto");
        }
        ScanBudget budget = new ScanBudget();
        return scanDirectory(root, root, 0, budget);
    }

    private ProjectTreeNode scanDirectory(Path root, Path directory, int depth, ScanBudget budget) {
        budget.nodes++;
        List<Path> entries = new ArrayList<>();
        boolean truncated = false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (Files.isSymbolicLink(entry)
                        || (Files.isDirectory(entry) && IGNORED_DIRS.contains(name))) {
                    continue;
                }
                if (entries.size() == MAX_CHILDREN || budget.nodes + entries.size() >= MAX_NODES) {
                    truncated = true;
                    break;
                }
                entries.add(entry);
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo leer la estructura del proyecto", exception);
        }

        entries.sort(ENTRY_ORDER);
        List<ProjectTreeNode> children = new ArrayList<>(entries.size());
        for (Path entry : entries) {
            if (budget.nodes >= MAX_NODES) {
                truncated = true;
                break;
            }
            boolean isDirectory = Files.isDirectory(entry);
            String relativePath = root.relativize(entry).toString().replace('\\', '/');
            if (isDirectory && depth < MAX_DEPTH) {
                children.add(scanDirectory(root, entry, depth + 1, budget));
            } else {
                budget.nodes++;
                children.add(new ProjectTreeNode(entry.getFileName().toString(), relativePath,
                        isDirectory, List.of(), isDirectory));
            }
        }

        return new ProjectTreeNode(
                directory.equals(root) ? root.getFileName().toString() : directory.getFileName().toString(),
                directory.equals(root) ? "" : root.relativize(directory).toString().replace('\\', '/'),
                true, List.copyOf(children), truncated);
    }

    private static final class ScanBudget {
        private int nodes;
    }
}
