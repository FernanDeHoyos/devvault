package com.devvault.discovery.application;

import com.devvault.discovery.plugin.DetectionResult;
import com.devvault.discovery.plugin.TechnologyPlugin;

import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Recorre recursivamente un Workspace, poda carpetas técnicas irrelevantes
 * (RF-07, RNF-02) y delega la detección de tecnología a cada TechnologyPlugin
 * registrado en el contexto de Spring — el engine no conoce ningún stack
 * concreto, solo orquesta.
 */
@Component
public class ScannerEngine {
    
    private static final Set<String> IGNORED_DIRS = Set.of(".git", "node_modules", "target", "build", "out", ".idea", ".vscode", ".dist");

    private final List<TechnologyPlugin> plugin;

    public ScannerEngine(List<TechnologyPlugin> plugin) {
        this.plugin = plugin;
    }

    public List<ScannedProject> scanProjects(Path rootPath) {
        List<ScannedProject> scannedProjects = new java.util.ArrayList<>();

        try {
            Files.walkFileTree(rootPath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (IGNORED_DIRS.contains(dir.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }

                    detectInDirectory(dir).ifPresent(scannedProjects::add);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (java.io.IOException e) {
            throw new RuntimeException("Error scanning projects", e);
        }

        return scannedProjects;
    }

    private Optional<ScannedProject> detectInDirectory(Path dir) {
        for (TechnologyPlugin techPlugin : plugin) {
            Optional<DetectionResult> result = techPlugin.detect(dir);
            if (result.isPresent()) {
                return Optional.of(new ScannedProject(dir, result.get()));
            }
        }
        return java.util.Optional.empty();
    }

    public record ScannedProject(Path path, DetectionResult detectionResult) {}
}
