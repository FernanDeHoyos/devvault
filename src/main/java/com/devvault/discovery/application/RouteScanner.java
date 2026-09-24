package com.devvault.discovery.application;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.devvault.discovery.application.dto.ProjectRouteResponse;

/** Combines static HTTP route declarations found across the project's supported stacks. */
@Component
public class RouteScanner {

    private final PhpRouteScanner phpRouteScanner;
    private final NodeRouteScanner nodeRouteScanner;

    public RouteScanner(PhpRouteScanner phpRouteScanner, NodeRouteScanner nodeRouteScanner) {
        this.phpRouteScanner = phpRouteScanner;
        this.nodeRouteScanner = nodeRouteScanner;
    }

    private static final Set<String> HTTP_MAPPINGS = Set.of(
            "GetMapping", "PostMapping", "PutMapping", "PatchMapping", "DeleteMapping");
    private static final Pattern CLASS_DECLARATION = Pattern.compile(
            "\\b(?:class|interface|enum|record|object)\\s+(\\w+)");
    private static final Pattern MAPPING_ANNOTATION = Pattern.compile(
            "@(?:(?:org\\.springframework\\.web\\.bind\\.annotation)\\.)?(RequestMapping|GetMapping|PostMapping|PutMapping|PatchMapping|DeleteMapping)\\b\\s*(\\([^)]*\\))?",
            Pattern.DOTALL);
    private static final Pattern STRING_VALUE = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern HANDLER_DECLARATION = Pattern.compile(
            "(?:fun\\s+|(?:public|protected|private|internal|static|final|synchronized|open|override|suspend|\\s)+[\\w.$<>?,\\[\\]\\s]+\\s+)(\\w+)\\s*\\(",
            Pattern.DOTALL);
    private static final Pattern CONDITION_ANNOTATION = Pattern.compile(
            "@(Profile|ConditionalOnProperty)\\b\\s*(\\([^)]*\\))?", Pattern.DOTALL);
    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", "build", "target", "out", "generated", "generated-sources");

    public List<ProjectRouteResponse> scan(Path projectRoot) {
        List<ProjectRouteResponse> routes = new ArrayList<>();
        routes.addAll(phpRouteScanner.scan(projectRoot));
        routes.addAll(nodeRouteScanner.scan(projectRoot));
        for (String sourceRoot : List.of("src/main/java", "src/main/kotlin")) {
            Path root = projectRoot.resolve(sourceRoot);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        return IGNORED_DIRS.contains(dir.getFileName().toString())
                                ? FileVisitResult.SKIP_SUBTREE
                                : FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        String name = file.getFileName().toString();
                        if (name.endsWith(".java") || name.endsWith(".kt")) {
                            try {
                                routes.addAll(scanFile(projectRoot, file, Files.readString(file)));
                            } catch (IOException ignored) {
                                // An unreadable source file should not prevent scanning the rest.
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException ignored) {
                // Keep any routes already discovered in the other source root.
            }
        }
        return routes.stream().distinct()
                .sorted((left, right) -> {
                    int pathOrder = left.path().compareTo(right.path());
                    return pathOrder != 0 ? pathOrder : left.httpMethod().compareTo(right.httpMethod());
                })
                .toList();
    }

    private List<ProjectRouteResponse> scanFile(Path projectRoot, Path file, String source) {
        List<ProjectRouteResponse> routes = new ArrayList<>();
        Matcher classMatcher = CLASS_DECLARATION.matcher(source);
        while (classMatcher.find()) {
            int classStart = classMatcher.start();
            int classBody = source.indexOf('{', classMatcher.end());
            if (classBody < 0) {
                continue;
            }
            int classEnd = matchingBrace(source, classBody);
            if (classEnd < 0) {
                classEnd = source.length();
            }

            String className = classMatcher.group(1);
            int classPrefixStart = Math.max(source.lastIndexOf('}', classStart), source.lastIndexOf(';', classStart)) + 1;
            String classPrefix = source.substring(classPrefixStart, classStart);
            String classMapping = lastClassMapping(classPrefix);
            List<String> classPaths = mappingPaths(classMapping);
            if (classPaths.isEmpty()) {
                classPaths = List.of("");
            }
            Set<String> inheritedConditions = conditions(classPrefix);

            Matcher mappingMatcher = MAPPING_ANNOTATION.matcher(source);
            mappingMatcher.region(classBody + 1, classEnd);
            while (mappingMatcher.find()) {
                String annotationName = mappingMatcher.group(1);
                String annotation = mappingMatcher.group();
                String methodName = findHandler(source, mappingMatcher.end(), classEnd);
                if (methodName == null) {
                    continue;
                }

                List<String> methodPaths = mappingPaths(annotation);
                if (methodPaths.isEmpty()) {
                    methodPaths = List.of("");
                }
                Set<String> routeConditions = new LinkedHashSet<>(inheritedConditions);
                routeConditions.addAll(conditions(source.substring(Math.max(classBody + 1, previousBoundary(source, mappingMatcher.start())), mappingMatcher.start())));
                List<String> methods = httpMethods(annotationName, annotation);

                for (String classPath : classPaths) {
                    for (String methodPath : methodPaths) {
                        String fullPath = joinPaths(classPath, methodPath);
                        for (String httpMethod : methods) {
                            routes.add(new ProjectRouteResponse(
                                    httpMethod,
                                    fullPath,
                                    className,
                                    methodName,
                                    projectRoot.relativize(file).toString().replace('\\', '/'),
                                    lineNumber(source, mappingMatcher.start()),
                                    routeConditions.isEmpty() ? "STATIC" : "CONDITIONAL",
                                    routeConditions.isEmpty() ? List.of() : List.copyOf(routeConditions)));
                        }
                    }
                }
            }
        }
        return routes;
    }

    private String lastClassMapping(String prefix) {
        Matcher matcher = MAPPING_ANNOTATION.matcher(prefix);
        String result = "";
        while (matcher.find()) {
            if ("RequestMapping".equals(matcher.group(1))) {
                result = matcher.group();
            }
        }
        return result;
    }

    private List<String> mappingPaths(String annotation) {
        if (annotation == null || annotation.isBlank()) {
            return List.of();
        }
        String arguments = annotation.contains("(")
                ? annotation.substring(annotation.indexOf('(') + 1, annotation.lastIndexOf(')'))
                : "";
        Matcher namedPath = Pattern.compile("(?:path|value)\\s*=\\s*(\\{[^}]*})").matcher(arguments);
        String selected = arguments;
        if (namedPath.find()) {
            selected = namedPath.group(1);
        }
        Matcher values = STRING_VALUE.matcher(selected);
        List<String> paths = new ArrayList<>();
        while (values.find()) {
            paths.add(values.group(1));
        }
        // For unnamed values, only strings before other named annotation attributes are paths.
        if (paths.isEmpty() && !arguments.contains("=")) {
            values = STRING_VALUE.matcher(arguments);
            while (values.find()) {
                paths.add(values.group(1));
            }
        }
        return paths;
    }

    private List<String> httpMethods(String annotationName, String annotation) {
        if (HTTP_MAPPINGS.contains(annotationName)) {
            return List.of(annotationName.replace("Mapping", "").toUpperCase());
        }
        Matcher methodAttribute = Pattern.compile("RequestMethod\\.(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)")
                .matcher(annotation);
        List<String> methods = new ArrayList<>();
        while (methodAttribute.find()) {
            methods.add(methodAttribute.group(1));
        }
        return methods.isEmpty() ? List.of("ALL") : methods.stream().distinct().toList();
    }

    private Set<String> conditions(String source) {
        Set<String> conditions = new LinkedHashSet<>();
        Matcher matcher = CONDITION_ANNOTATION.matcher(source);
        while (matcher.find()) {
            conditions.add(matcher.group().replaceAll("\\s+", " ").trim());
        }
        return conditions;
    }

    private String findHandler(String source, int from, int classEnd) {
        int body = source.indexOf('{', from);
        int semicolon = source.indexOf(';', from);
        int end = body < 0 ? classEnd : Math.min(body, classEnd);
        if (semicolon >= 0 && semicolon < end) {
            end = semicolon;
        }
        if (end <= from) {
            return null;
        }
        String declaration = source.substring(from, end);
        Matcher matcher = HANDLER_DECLARATION.matcher(declaration);
        String name = null;
        while (matcher.find()) {
            name = matcher.group(1);
        }
        return name;
    }

    private int previousBoundary(String source, int position) {
        int closeBrace = source.lastIndexOf('}', position);
        int openBrace = source.lastIndexOf('{', position);
        int semicolon = source.lastIndexOf(';', position);
        return Math.max(Math.max(closeBrace, openBrace), semicolon) + 1;
    }

    private int matchingBrace(String source, int open) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = open; i < source.length(); i++) {
            char current = source.charAt(i);
            if (current == '"' && !escaped) {
                inString = !inString;
            }
            if (!inString) {
                if (current == '{') depth++;
                if (current == '}' && --depth == 0) return i;
            }
            escaped = current == '\\' && !escaped;
            if (current != '\\') escaped = false;
        }
        return -1;
    }

    private String joinPaths(String classPath, String methodPath) {
        String left = classPath == null ? "" : classPath.trim();
        String right = methodPath == null ? "" : methodPath.trim();
        if (left.isEmpty()) return normalizePath(right);
        if (right.isEmpty()) return normalizePath(left);
        return normalizePath(left + "/" + right);
    }

    private String normalizePath(String path) {
        String normalized = path.replaceAll("/+/", "/");
        if (!normalized.startsWith("/")) normalized = "/" + normalized;
        return normalized;
    }

    private int lineNumber(String source, int offset) {
        return (int) source.substring(0, offset).chars().filter(character -> character == '\n').count() + 1;
    }
}
