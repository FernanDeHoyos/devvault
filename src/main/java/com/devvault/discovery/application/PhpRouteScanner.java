package com.devvault.discovery.application;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.devvault.discovery.application.dto.ProjectRouteResponse;

/** Scans Laravel route files without bootstrapping or executing the application. */
@Component
public class PhpRouteScanner {

    private static final Pattern PREFIX_GROUP = Pattern.compile(
            "Route\\s*::\\s*(?:middleware\\s*\\([^)]*\\)\\s*->\\s*)*prefix\\s*\\(\\s*(['\"])(.*?)\\1\\s*\\)(?:\\s*->\\s*\\w+\\s*\\([^)]*\\))*\\s*->\\s*group\\s*\\(\\s*function\\s*(?:\\([^)]*\\))?\\s*\\{",
            Pattern.DOTALL);
    private static final Pattern ARRAY_PREFIX_GROUP = Pattern.compile(
            "Route\\s*::\\s*group\\s*\\(\\s*\\[\\s*['\"]prefix['\"]\\s*=>\\s*(['\"])(.*?)\\1\\s*]\\s*,\\s*function\\s*(?:\\([^)]*\\))?\\s*\\{",
            Pattern.DOTALL);
    private static final Pattern SIMPLE_ROUTE = Pattern.compile(
            "Route\\s*::\\s*(get|post|put|patch|delete|options|head|any)\\s*\\(\\s*(['\"])(.*?)\\2",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern MATCH_ROUTE = Pattern.compile(
            "Route\\s*::\\s*match\\s*\\(\\s*\\[([^]]*)]\\s*,\\s*(['\"])(.*?)\\2",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern RESOURCE_ROUTE = Pattern.compile(
            "Route\\s*::\\s*(apiResource|resource)\\s*\\(\\s*(['\"])(.*?)\\2",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern PHP_HANDLER = Pattern.compile(
            "([\\w\\\\]+)::class\\s*,\\s*['\"]([\\w$]+)['\"]|['\"]([\\w\\\\]+@[\\w$]+)['\"]");
    private static final Set<String> ROUTE_DIRS = Set.of("routes");

    public List<ProjectRouteResponse> scan(Path projectRoot) {
        List<ProjectRouteResponse> routes = new ArrayList<>();
        for (String routeDirectory : ROUTE_DIRS) {
            Path root = projectRoot.resolve(routeDirectory);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try {
                Files.walkFileTree(root, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (file.getFileName().toString().endsWith(".php")) {
                            try {
                                String source = Files.readString(file);
                                scanRange(projectRoot, file, source, 0, source.length(), "", routes);
                            } catch (IOException ignored) {
                                // A file that cannot be read does not prevent scanning the other route files.
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException ignored) {
                // Return the route declarations discovered before the file-system error.
            }
        }
        return routes;
    }

    private void scanRange(Path projectRoot, Path file, String source, int start, int end,
            String prefix, List<ProjectRouteResponse> routes) {
        List<int[]> groupRanges = new ArrayList<>();
        scanGroups(PREFIX_GROUP, projectRoot, file, source, start, end, prefix, groupRanges, routes);
        scanGroups(ARRAY_PREFIX_GROUP, projectRoot, file, source, start, end, prefix, groupRanges, routes);

        scanSimpleRoutes(projectRoot, file, source, start, end, prefix, groupRanges, routes);
        scanMatchRoutes(projectRoot, file, source, start, end, prefix, groupRanges, routes);
        scanResourceRoutes(projectRoot, file, source, start, end, prefix, groupRanges, routes);
    }

    private void scanGroups(Pattern pattern, Path projectRoot, Path file, String source, int start, int end,
            String prefix, List<int[]> groupRanges, List<ProjectRouteResponse> routes) {
        Matcher matcher = pattern.matcher(source);
        matcher.region(start, end);
        while (matcher.find()) {
            if (insideGroup(matcher.start(), groupRanges)) continue;
            int openBrace = matcher.end() - 1;
            int closeBrace = matchingBrace(source, openBrace, end);
            if (closeBrace < 0) {
                continue;
            }
            groupRanges.add(new int[] { matcher.start(), closeBrace + 1 });
            String nestedPrefix = joinPaths(prefix, matcher.group(2));
            scanRange(projectRoot, file, source, openBrace + 1, closeBrace, nestedPrefix, routes);
        }
    }

    private void scanSimpleRoutes(Path projectRoot, Path file, String source, int start, int end, String prefix,
            List<int[]> groupRanges, List<ProjectRouteResponse> routes) {
        Matcher matcher = SIMPLE_ROUTE.matcher(source);
        matcher.region(start, end);
        while (matcher.find()) {
            if (insideGroup(matcher.start(), groupRanges)) continue;
            String method = matcher.group(1).toUpperCase();
            if ("ANY".equals(method)) method = "ALL";
            String path = joinPaths(prefix, matcher.group(3));
            String handler = handler(source, matcher.end(), end);
            addRoute(projectRoot, file, source, matcher.start(), method, path, "Laravel Route", handler, routes);
        }
    }

    private void scanMatchRoutes(Path projectRoot, Path file, String source, int start, int end, String prefix,
            List<int[]> groupRanges, List<ProjectRouteResponse> routes) {
        Matcher matcher = MATCH_ROUTE.matcher(source);
        matcher.region(start, end);
        while (matcher.find()) {
            if (insideGroup(matcher.start(), groupRanges)) continue;
            String path = joinPaths(prefix, matcher.group(3));
            String handler = handler(source, matcher.end(), end);
            Matcher methods = Pattern.compile("['\"](get|post|put|patch|delete|options|head)['\"]", Pattern.CASE_INSENSITIVE)
                    .matcher(matcher.group(1));
            while (methods.find()) {
                addRoute(projectRoot, file, source, matcher.start(), methods.group(1).toUpperCase(), path,
                        "Laravel Route", handler, routes);
            }
        }
    }

    private void scanResourceRoutes(Path projectRoot, Path file, String source, int start, int end, String prefix,
            List<int[]> groupRanges, List<ProjectRouteResponse> routes) {
        Matcher matcher = RESOURCE_ROUTE.matcher(source);
        matcher.region(start, end);
        while (matcher.find()) {
            if (insideGroup(matcher.start(), groupRanges)) continue;
            String operation = "apiResource".equalsIgnoreCase(matcher.group(1)) ? "apiResource" : "resource";
            addRoute(projectRoot, file, source, matcher.start(), operation.toUpperCase(),
                    joinPaths(prefix, matcher.group(3)), "Laravel Route", operation, routes);
        }
    }

    private String handler(String source, int from, int end) {
        int statementEnd = source.indexOf(';', from);
        if (statementEnd < 0 || statementEnd > end) statementEnd = Math.min(end, from + 500);
        String action = source.substring(from, statementEnd);
        Matcher matcher = PHP_HANDLER.matcher(action);
        if (matcher.find()) {
            if (matcher.group(1) != null) {
                return matcher.group(1).substring(matcher.group(1).lastIndexOf('\\') + 1) + "." + matcher.group(2);
            }
            return matcher.group(3).replace('\\', '.').replace('@', '.');
        }
        return action.contains("function") || action.contains("fn(") || action.contains("fn (")
                ? "closure"
                : "route callback";
    }

    private boolean insideGroup(int position, List<int[]> ranges) {
        return ranges.stream().anyMatch(range -> position >= range[0] && position < range[1]);
    }

    private int matchingBrace(String source, int open, int limit) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = open; i < limit; i++) {
            char current = source.charAt(i);
            if (quote != 0) {
                if (current == quote && !escaped) quote = 0;
                escaped = current == '\\' && !escaped;
                if (current != '\\') escaped = false;
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                quote = current;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private void addRoute(Path projectRoot, Path file, String source, int offset, String method, String path,
            String controller, String handler, List<ProjectRouteResponse> routes) {
        routes.add(new ProjectRouteResponse(method, path, controller, handler,
                projectRoot.relativize(file).toString().replace('\\', '/'), lineNumber(source, offset), "STATIC", List.of()));
    }

    private String joinPaths(String prefix, String path) {
        String left = prefix == null ? "" : prefix.trim();
        String right = path == null ? "" : path.trim();
        String joined = (left + "/" + right).replaceAll("/+/", "/");
        return joined.startsWith("/") ? joined : "/" + joined;
    }

    private int lineNumber(String source, int offset) {
        return (int) source.substring(0, offset).chars().filter(character -> character == '\n').count() + 1;
    }
}
