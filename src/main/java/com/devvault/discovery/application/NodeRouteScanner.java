package com.devvault.discovery.application;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.devvault.discovery.application.dto.ProjectRouteResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Statically discovers common Express, Fastify, and NestJS HTTP mappings. */
@Component
public class NodeRouteScanner {

    private static final Set<String> SOURCE_EXTENSIONS = Set.of(".js", ".jsx", ".mjs", ".cjs", ".ts", ".tsx", ".mts", ".cts");
    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", "node_modules", "build", "dist", "out", "coverage", ".next", ".nuxt", ".output", "vendor");
    private static final Set<String> ROOT_RECEIVERS = Set.of("app", "server", "fastify", "instance");
    private static final Pattern MOUNT = Pattern.compile(
            "\\b([\\w$]+)\\s*\\.\\s*use\\s*\\(\\s*(?:(['\"`])([^'\"`\\r\\n]*)\\2\\s*,\\s*)?([\\w$]+)\\s*\\)");
    private static final Pattern ROUTER_VARIABLE = Pattern.compile(
            "\\b(?:const|let|var)\\s+([\\w$]+)\\s*=\\s*(?:express\\s*\\(\\s*\\)|express\\s*\\.\\s*Router\\s*\\(|Router\\s*\\(|fastify\\s*\\(|Fastify\\s*\\()",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ROUTE_CALL = Pattern.compile(
            "\\b([\\w$]+)\\s*\\.\\s*(get|post|put|patch|delete|options|head|all)\\s*\\(\\s*(['\"`])([^'\"`\\r\\n]*)\\3\\s*,",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ROUTE_CHAIN = Pattern.compile(
            "\\b([\\w$]+)\\s*\\.\\s*route\\s*\\(\\s*(['\"`])([^'\"`\\r\\n]*)\\2\\s*\\)");
    private static final Pattern CHAIN_METHOD = Pattern.compile(
            "\\.\\s*(get|post|put|patch|delete|options|head|all)\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern FASTIFY_ROUTE = Pattern.compile(
            "\\b([\\w$]+)\\s*\\.\\s*route\\s*\\(\\s*\\{([^}]+)}\\s*\\)", Pattern.DOTALL);
    private static final Pattern OBJECT_METHOD = Pattern.compile("\\bmethod\\s*:\\s*(['\"])(.*?)\\1", Pattern.DOTALL);
    private static final Pattern OBJECT_PATH = Pattern.compile("\\b(?:url|path)\\s*:\\s*(['\"`])([^'\"`\\r\\n]*)\\1");
    private static final Pattern HANDLER_REFERENCE = Pattern.compile("([\\w$]+(?:\\.[\\w$]+)*)\\s*(?=,|\\))");
    private static final Pattern NEST_CONTROLLER = Pattern.compile(
            "@Controller\\s*(?:\\(([^)]*)\\))?\\s*(?:(?:@[\\w.]+(?:\\([^)]*\\))?)\\s*)*(?:export\\s+)?(?:default\\s+)?class\\s+(\\w+)",
            Pattern.DOTALL);
    private static final Pattern NEST_METHOD = Pattern.compile(
            "@(Get|Post|Put|Patch|Delete|Options|Head|All)\\s*(?:\\(([^)]*)\\))?\\s*(?:(?:@[\\w.]+(?:\\([^)]*\\))?)\\s*)*(?:(?:public|private|protected|async|static)\\s+)*(\\w+)\\s*\\(",
            Pattern.DOTALL);
    private static final Pattern STRING_LITERAL = Pattern.compile("(['\"`])([^'\"`\\r\\n]*)\\1");
    private static final Pattern NAMED_PATH = Pattern.compile("\\b(?:path|url)\\s*:\\s*(['\"`])([^'\"`\\r\\n]*)\\1");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<ProjectRouteResponse> scan(Path projectRoot) {
        if (!hasSupportedServerFramework(projectRoot)) {
            return List.of();
        }
        List<SourceFile> files = readSourceFiles(projectRoot);
        Map<String, Set<String>> receiverPrefixes = findReceiverPrefixes(files);
        List<ProjectRouteResponse> routes = new ArrayList<>();
        for (SourceFile file : files) {
            scanNestMappings(projectRoot, file, routes);
            scanNodeCalls(projectRoot, file, receiverPrefixes, routes);
        }
        return routes;
    }

    private List<SourceFile> readSourceFiles(Path projectRoot) {
        List<SourceFile> files = new ArrayList<>();
        try {
            Files.walkFileTree(projectRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    Path name = dir.getFileName();
                    return name != null && IGNORED_DIRS.contains(name.toString())
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    String name = file.getFileName().toString();
                    int extensionIndex = name.lastIndexOf('.');
                    String extension = extensionIndex < 0 ? "" : name.substring(extensionIndex).toLowerCase();
                    if (SOURCE_EXTENSIONS.contains(extension) && attrs.size() <= 2_000_000) {
                        try {
                            files.add(new SourceFile(file, Files.readString(file)));
                        } catch (IOException ignored) {
                            // Continue with other source files.
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Keep the source files collected before the file-system error.
        }
        return files;
    }

    private Map<String, Set<String>> findReceiverPrefixes(List<SourceFile> files) {
        Map<String, Set<String>> prefixes = new HashMap<>();
        List<Mount> mounts = new ArrayList<>();
        for (SourceFile file : files) {
            Matcher routerVariables = ROUTER_VARIABLE.matcher(file.source());
            while (routerVariables.find()) {
                prefixes.computeIfAbsent(routerVariables.group(1), ignored -> new HashSet<>()).add("");
            }
            Matcher matcher = MOUNT.matcher(file.source());
            while (matcher.find()) {
                mounts.add(new Mount(matcher.group(1), matcher.group(4), matcher.group(3) == null ? "" : matcher.group(3)));
            }
        }

        for (int pass = 0; pass <= mounts.size(); pass++) {
            boolean changed = false;
            for (Mount mount : mounts) {
                Set<String> parentPrefixes = prefixes.get(mount.parent());
                if (parentPrefixes == null && ROOT_RECEIVERS.contains(mount.parent())) {
                    parentPrefixes = Set.of("");
                }
                if (parentPrefixes == null) continue;
                Set<String> childPrefixes = prefixes.computeIfAbsent(mount.child(), ignored -> new HashSet<>());
                if (!mount.path().isEmpty()) childPrefixes.remove("");
                for (String parentPrefix : parentPrefixes) {
                    changed |= childPrefixes.add(joinPath(parentPrefix, mount.path()));
                }
            }
            if (!changed) break;
        }
        for (String rootReceiver : ROOT_RECEIVERS) {
            prefixes.computeIfAbsent(rootReceiver, ignored -> new HashSet<>()).add("");
        }
        return prefixes;
    }

    private void scanNodeCalls(Path projectRoot, SourceFile file, Map<String, Set<String>> receiverPrefixes,
            List<ProjectRouteResponse> routes) {
        String source = file.source();

        Matcher direct = ROUTE_CALL.matcher(source);
        while (direct.find()) {
            String receiver = direct.group(1);
            if (!receiverPrefixes.containsKey(receiver)) continue;
            String method = direct.group(2).toUpperCase();
            if ("ALL".equals(method)) method = "ALL";
            String path = direct.group(4);
            String handler = handlerReference(source, direct.end());
            addForPrefixes(projectRoot, file, source, direct.start(), method, path, receiver, handler,
                    receiverPrefixes, routes);
        }

        Matcher chains = ROUTE_CHAIN.matcher(source);
        while (chains.find()) {
            String receiver = chains.group(1);
            if (!receiverPrefixes.containsKey(receiver)) continue;
            String path = chains.group(3);
            int statementEnd = source.indexOf(';', chains.end());
            if (statementEnd < 0) statementEnd = Math.min(source.length(), chains.end() + 1500);
            Matcher methods = CHAIN_METHOD.matcher(source.substring(chains.end(), statementEnd));
            while (methods.find()) {
                addForPrefixes(projectRoot, file, source, chains.start(), methods.group(1).toUpperCase(), path,
                        receiver, "route handler", receiverPrefixes, routes);
            }
        }

        Matcher fastify = FASTIFY_ROUTE.matcher(source);
        while (fastify.find()) {
            String receiver = fastify.group(1);
            if (!receiverPrefixes.containsKey(receiver)) continue;
            String options = fastify.group(2);
            Matcher path = OBJECT_PATH.matcher(options);
            Matcher method = OBJECT_METHOD.matcher(options);
            if (!path.find() || !method.find()) continue;
            for (String verb : method.group(2).split("[,|]")) {
                String normalizedVerb = verb.trim().replaceAll("[^A-Za-z]", "").toUpperCase();
                if (normalizedVerb.matches("GET|POST|PUT|PATCH|DELETE|OPTIONS|HEAD|ALL")) {
                    addForPrefixes(projectRoot, file, source, fastify.start(), normalizedVerb, path.group(2),
                            receiver, "route handler", receiverPrefixes, routes);
                }
            }
        }
    }

    private void scanNestMappings(Path projectRoot, SourceFile file, List<ProjectRouteResponse> routes) {
        String source = file.source();
        Matcher controllers = NEST_CONTROLLER.matcher(source);
        while (controllers.find()) {
            int classBody = source.indexOf('{', controllers.end());
            if (classBody < 0) continue;
            int classEnd = matchingBrace(source, classBody);
            if (classEnd < 0) classEnd = source.length();
            String classPath = nestControllerPath(controllers.group(1));
            String controller = controllers.group(2);

            Matcher methods = NEST_METHOD.matcher(source);
            methods.region(classBody + 1, classEnd);
            while (methods.find()) {
                String verb = methods.group(1).toUpperCase();
                String methodPath = firstString(methods.group(2));
                addRoute(projectRoot, file, source, methods.start(), verb,
                        joinPath(classPath, methodPath), controller, methods.group(3), routes);
            }
        }
    }

    private String nestControllerPath(String arguments) {
        if (arguments == null) return "";
        Matcher namedPath = NAMED_PATH.matcher(arguments);
        return namedPath.find() ? namedPath.group(2) : firstString(arguments);
    }

    private String firstString(String source) {
        if (source == null) return "";
        Matcher matcher = STRING_LITERAL.matcher(source);
        return matcher.find() ? matcher.group(2) : "";
    }

    private String handlerReference(String source, int from) {
        int end = source.indexOf(';', from);
        if (end < 0) end = Math.min(source.length(), from + 1200);
        String arguments = source.substring(from, end);
        String trimmedArguments = arguments.stripLeading();
        if (arguments.contains("=>") || trimmedArguments.startsWith("function")
                || trimmedArguments.startsWith("async ")) {
            return "inline";
        }
        Matcher references = HANDLER_REFERENCE.matcher(arguments);
        String handler = "route handler";
        while (references.find()) {
            handler = references.group(1);
        }
        return handler;
    }

    private void addForPrefixes(Path projectRoot, SourceFile file, String source, int offset, String method,
            String path, String receiver, String handler, Map<String, Set<String>> receiverPrefixes,
            List<ProjectRouteResponse> routes) {
        Set<String> prefixes = receiverPrefixes.getOrDefault(receiver, Set.of(""));
        for (String prefix : prefixes) {
            addRoute(projectRoot, file, source, offset, method, joinPath(prefix, path), receiver, handler, routes);
        }
    }

    private void addRoute(Path projectRoot, SourceFile file, String source, int offset, String method,
            String path, String controller, String handler, List<ProjectRouteResponse> routes) {
        routes.add(new ProjectRouteResponse(method, path, controller, handler,
                projectRoot.relativize(file.path()).toString().replace('\\', '/'), lineNumber(source, offset),
                "STATIC", List.of()));
    }

    private String joinPath(String prefix, String path) {
        String left = prefix == null ? "" : prefix.trim();
        String right = path == null ? "" : path.trim();
        String joined = (left + "/" + right).replaceAll("/+/", "/");
        return joined.startsWith("/") ? joined : "/" + joined;
    }

    private int matchingBrace(String source, int open) {
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int i = open; i < source.length(); i++) {
            char current = source.charAt(i);
            if (quote != 0) {
                if (current == quote && !escaped) quote = 0;
                escaped = current == '\\' && !escaped;
                if (current != '\\') escaped = false;
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') quote = current;
            else if (current == '{') depth++;
            else if (current == '}' && --depth == 0) return i;
        }
        return -1;
    }

    private int lineNumber(String source, int offset) {
        return (int) source.substring(0, offset).chars().filter(character -> character == '\n').count() + 1;
    }

    private boolean hasSupportedServerFramework(Path projectRoot) {
        Path manifest = projectRoot.resolve("package.json");
        if (!Files.isRegularFile(manifest)) return false;
        try {
            JsonNode root = objectMapper.readTree(manifest.toFile());
            for (String dependency : List.of("express", "fastify", "@nestjs/core")) {
                if (root.path("dependencies").has(dependency) || root.path("devDependencies").has(dependency)) {
                    return true;
                }
            }
        } catch (IOException ignored) {
            return false;
        }
        return false;
    }

    private record SourceFile(Path path, String source) {}
    private record Mount(String parent, String child, String path) {}
}
