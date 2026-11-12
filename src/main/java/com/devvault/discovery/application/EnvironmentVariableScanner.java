package com.devvault.discovery.application;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Inventories variable names and source paths without storing configuration values. */
final class EnvironmentVariableScanner {
    private static final Pattern DOTENV = Pattern.compile(
            "(?m)^\\h*(?:export\\h+)?([A-Za-z_][A-Za-z0-9_]*)\\h*=");
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "(?<!\\$)\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?=[:?+}\\-])");
    private static final Pattern JAVASCRIPT_ENV = Pattern.compile(
            "\\b(?:process|import\\.meta)\\.env\\.([A-Za-z_][A-Za-z0-9_]*)");
    private static final Pattern ENV_CALL = Pattern.compile(
            "\\b(?:System\\.getenv|os\\.getenv|os\\.environ\\.get|os\\.Getenv|os\\.LookupEnv)"
                    + "\\s*\\(\\s*[\"']([A-Za-z_][A-Za-z0-9_]*)[\"']");
    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_VARIABLES = 250;
    private static final int MAX_SOURCES = 10;
    private static final long MAX_BYTES = 128 * 1024;

    private EnvironmentVariableScanner() {
    }

    static Map<String, List<String>> scan(Path root, Set<String> ignoredDirectories) {
        Map<String, Set<String>> sources = new TreeMap<>();
        try {
            Files.walkFileTree(root, Set.of(), 12, new SimpleFileVisitor<>() {
                private int visited;

                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (++visited > MAX_ENTRIES) {
                        return FileVisitResult.TERMINATE;
                    }
                    return !directory.equals(root) && ignoredDirectories.contains(directory.getFileName().toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (++visited > MAX_ENTRIES) {
                        return FileVisitResult.TERMINATE;
                    }
                    if (attributes.isRegularFile() && attributes.size() <= MAX_BYTES) {
                        collect(root, file, sources);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exception) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Keep findings from readable files.
        }
        Map<String, List<String>> results = new LinkedHashMap<>();
        sources.forEach((name, files) -> results.put(name, files.stream().sorted().toList()));
        return results;
    }

    private static void collect(Path root, Path file, Map<String, Set<String>> sources) {
        String filename = file.getFileName().toString();
        List<Pattern> patterns = patternsFor(filename);
        if (patterns.isEmpty()) {
            return;
        }
        try {
            String content = Files.readString(file);
            String relative = root.relativize(file).toString().replace('\\', '/');
            for (Pattern pattern : patterns) {
                var matches = pattern.matcher(content);
                while (matches.find()) {
                    String name = matches.group(1);
                    if (sources.size() < MAX_VARIABLES || sources.containsKey(name)) {
                        Set<String> files = sources.computeIfAbsent(name, ignored -> new LinkedHashSet<>());
                        if (files.size() < MAX_SOURCES) {
                            files.add(relative);
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // Skip unreadable or non-UTF-8 files.
        }
    }

    private static List<Pattern> patternsFor(String filename) {
        if (filename.equals(".env") || filename.startsWith(".env.")) {
            return List.of(DOTENV);
        }
        if (filename.matches("(?:application|bootstrap)(?:-[^.]+)?\\.(?:properties|ya?ml)")
                || filename.matches("(?:docker-)?compose(?:[.-][^.]+)*\\.ya?ml")) {
            return List.of(PLACEHOLDER);
        }
        if (filename.matches(".+\\.(?:[cm]?[jt]s|jsx|tsx)")) {
            return List.of(JAVASCRIPT_ENV);
        }
        if (filename.matches(".+\\.(?:java|kt|py|go)")) {
            return List.of(ENV_CALL);
        }
        return List.of();
    }
}
