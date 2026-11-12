package com.devvault.discovery.plugin;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Reads dependency manifests and architecture declarations without executing project code. */
@Component
public class ProjectArchitecturePlugin implements TechnologyPlugin {

    private static final int MAX_DISCOVERED_FILES = 20_000;
    private static final int MAX_DEPENDENCIES = 500;
    private static final int MAX_MODULES = 100;
    private static final long MAX_TEXT_FILE_BYTES = 1_000_000;
    private static final long MAX_JSON_FILE_BYTES = 10_000_000;
    private static final List<String> COMPOSE_FILES = List.of(
            "compose.yml", "compose.yaml", "compose.override.yml", "compose.override.yaml",
            "docker-compose.yml", "docker-compose.yaml",
            "docker-compose.override.yml", "docker-compose.override.yaml");
    private static final Set<String> IGNORED_DIRS = Set.of(
            ".git", "node_modules", "vendor", "target", "build", "dist", "out", ".gradle",
            ".next", ".nuxt", ".cache", "coverage", "__pycache__", ".venv", "venv");
    private static final Set<String> MANIFESTS = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "package.json", "composer.json",
            "pyproject.toml", "requirements.txt", "Pipfile", "go.mod", "go.work", "Cargo.toml");
    private static final Pattern GRADLE_DEPENDENCY = Pattern.compile(
            "(?m)^\\s*(implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|"
                    + "developmentOnly|annotationProcessor|kapt|classpath)\\s*(?:\\(\\s*)?['\"]"
                    + "([^:'\"\\s]+):([^:'\"\\s]+)(?::([^'\"\\s)]+))?['\"]");
    private static final Pattern GRADLE_ALIAS = Pattern.compile(
            "(?m)^\\s*(implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly)\\s*"
                    + "\\(?\\s*libs\\.([A-Za-z0-9_.-]+)");
    private static final Pattern GRADLE_INCLUDE = Pattern.compile(
            "(?m)^\\s*include\\s*\\(?\\s*([^\\r\\n)]*)\\)?");
    private static final Pattern REQUIREMENT = Pattern.compile(
            "^\\s*([A-Za-z0-9_.-]+)\\s*(?:\\[[^]]+])?\\s*((?:===|==|~=|!=|>=|<=|>|<)\\s*[^;\\s]+)?");
    private static final Pattern DATABASE_URL = Pattern.compile(
            "(?i)(?:jdbc:)?(postgresql|postgres|pg|mysql2|mysql|mariadb|mongodb|mongodb\\+srv|redis|sqlite|"
                    + "oracle|sqlserver|mssql|h2)(?:[+:/]|\\b)");
    private static final Pattern PROPERTY_DATABASE_URL = Pattern.compile(
            "(?im)^\\s*([a-z0-9_.-]*(?:datasource\\.(?:url|jdbc-url)|(?:sqlalchemy_)?database_(?:url|uri)|db_url|jdbc_url))"
                    + "\\s*[=:]\\s*(.*?)\\s*$");
    private static final Pattern DATABASE_DRIVER_PROPERTY = Pattern.compile(
            "(?im)^\\s*([a-z0-9_.-]*(?:driver-class-name|driverclassname))\\s*[=:]\\s*(.*?)\\s*$");
    private static final Pattern DATABASE_TYPE_PROPERTY = Pattern.compile(
            "(?im)[\"']?(?:type|client|dialect|provider)[\"']?\\s*[:=]\\s*[\"']([^\"'\\s,}]+)");
    private static final Pattern YAML_KEY_VALUE = Pattern.compile(
            "^(\\s*)([A-Za-z0-9_.-]+)\\s*:\\s*(.*?)\\s*(?:#.*)?$");
    private static final Pattern DATABASE_IMAGE = Pattern.compile(
            "(?i)(postgres(?:ql)?|mysql|mariadb|mongo(?:db)?|redis|cassandra|oracle|mssql|sqlserver|"
                    + "cockroachdb|couchdb|elasticsearch)");
    private static final Pattern SQL_DRIVER = Pattern.compile(
            "(?i)(postgresql|postgres|mysql|mariadb|mongodb|redis|sqlite|oracle|sqlserver|h2)");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Override
    public String pluginName() {
        return "project-architecture-detector";
    }

    @Override
    public String targetMarkerFiles() {
        return "pom.xml, build.gradle, package.json, composer.json, pyproject.toml, Pipfile, Compose";
    }

    @Override
    public int detectionPriority() {
        return 5;
    }

    @Override
    public Optional<DetectionResult> detect(Path projectDir) {
        boolean rootHasManifest = MANIFESTS.stream().anyMatch(name -> Files.isRegularFile(projectDir.resolve(name)));
        boolean rootHasCompose = hasComposeFile(projectDir);
        if (!rootHasManifest && !rootHasCompose) {
            return Optional.empty();
        }

        Map<String, Object> markers = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> dependencies = new ArrayList<>();
        List<Map<String, Object>> modules = new ArrayList<>();
        List<Map<String, Object>> tools = new ArrayList<>();
        List<Map<String, Object>> databases = new ArrayList<>();
        List<Map<String, Object>> configurations = new ArrayList<>();
        List<Map<String, Object>> services = new ArrayList<>();
        Set<String> detectedFiles = new LinkedHashSet<>();

        collectManifestFiles(projectDir, detectedFiles, warnings);
        if (detectedFiles.isEmpty() && !rootHasCompose) {
            return Optional.empty();
        }

        for (String manifest : detectedFiles) {
            switch (Path.of(manifest).getFileName().toString().toLowerCase(Locale.ROOT)) {
                case "pom.xml" -> parseMaven(projectDir.resolve(manifest), manifest, dependencies, modules, tools, warnings);
                case "build.gradle", "build.gradle.kts" ->
                    parseGradle(projectDir, projectDir.resolve(manifest), manifest, dependencies, modules, tools, warnings);
                case "package.json" ->
                    parseNode(projectDir, manifest, dependencies, modules, tools, warnings);
                case "composer.json" ->
                    parseComposer(projectDir, manifest, dependencies, modules, tools, warnings);
                case "pyproject.toml" ->
                    parsePyProject(projectDir.resolve(manifest), manifest, dependencies, modules, tools, warnings);
                case "requirements.txt" ->
                    parseRequirements(projectDir.resolve(manifest), manifest, dependencies, tools, warnings);
                case "pipfile" -> parsePipfile(projectDir.resolve(manifest), manifest, dependencies, tools, warnings);
                default -> {
                }
            }
        }

        collectBuildModules(projectDir, modules, warnings);
        collectSourceAreas(projectDir, modules, warnings);
        collectDatabaseConfigurations(projectDir, databases, configurations, warnings);
        collectDatabaseDependencies(dependencies, databases);
        collectComposeServices(projectDir, services, databases, configurations, warnings);
        collectBuildTools(projectDir, tools, configurations);

        markers.put("marker", detectedFiles.isEmpty() ? "compose configuration" : detectedFiles.iterator().next());
        markers.put("architectureModules", uniqueByPath(modules));
        markers.put("dependencies", uniqueDependencies(dependencies));
        markers.put("tools", uniqueByNameAndFile(tools));
        markers.put("databases", uniqueByNameAndFile(databases));
        markers.put("configurations", uniqueByNameAndFile(configurations));
        markers.put("services", uniqueByNameAndFile(services));
        markers.put("architectureFiles", List.copyOf(detectedFiles));
        if (!warnings.isEmpty()) {
            markers.put("warnings", warnings.stream().distinct().toList());
        }

        return Optional.of(new DetectionResult(null, null, null, markers));
    }

    private void collectManifestFiles(Path root, Set<String> files, List<String> warnings) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                private int visitedFiles;

                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(root) && IGNORED_DIRS.contains(directory.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (++visitedFiles > MAX_DISCOVERED_FILES) {
                        warnings.add("Se alcanzó el límite de archivos inspeccionados para descubrir manifiestos.");
                        return FileVisitResult.TERMINATE;
                    }
                    if (attributes.isRegularFile() && MANIFESTS.contains(file.getFileName().toString())) {
                        files.add(relative(root, file));
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            warnings.add("No se pudieron enumerar todos los manifiestos del proyecto.");
        }
    }

    private void parseMaven(Path pom, String relative, List<Map<String, Object>> dependencies,
            List<Map<String, Object>> modules, List<Map<String, Object>> tools, List<String> warnings) {
        try {
            if (!isWithinReadLimit(pom, MAX_TEXT_FILE_BYTES, relative, warnings)) {
                return;
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            Element project = factory.newDocumentBuilder().parse(pom.toFile()).getDocumentElement();
            Element moduleContainer = directChild(project, "modules");
            NodeList moduleList = moduleContainer == null ? null : moduleContainer.getChildNodes();
            for (int i = 0; moduleList != null && i < moduleList.getLength() && modules.size() < MAX_MODULES; i++) {
                if (!(moduleList.item(i) instanceof Element moduleElement) || !"module".equals(moduleElement.getTagName())) {
                    continue;
                }
                String modulePath = moduleElement.getTextContent().trim();
                if (!modulePath.isBlank()) {
                    String normalized = normalizeRelative(parentPath(relative), modulePath);
                    if (!normalized.isBlank()) {
                        modules.add(module(normalized, "MAVEN_MODULE", relative, "modules"));
                    }
                }
            }
            Element dependencyContainer = directChild(project, "dependencies");
            NodeList dependencyList = dependencyContainer == null ? null : dependencyContainer.getChildNodes();
            for (int i = 0; dependencyList != null && i < dependencyList.getLength()
                    && dependencies.size() < MAX_DEPENDENCIES; i++) {
                if (!(dependencyList.item(i) instanceof Element dependency) || !"dependency".equals(dependency.getTagName())) {
                    continue;
                }
                String group = childText(dependency, "groupId");
                String artifact = childText(dependency, "artifactId");
                if (artifact == null) {
                    continue;
                }
                String version = childText(dependency, "version");
                String scope = childText(dependency, "scope");
                String scopeLabel = "test".equals(scope) || "provided".equals(scope) ? "DEVELOPMENT" : "PRODUCTION";
                dependencies.add(dependency(
                        group == null ? artifact : group + ":" + artifact,
                        version, scopeLabel, "Maven", relative, "dependency"));
            }
            tools.add(tool("Maven", null, relative, "build system"));
            Element build = directChild(project, "build");
            Element pluginContainer = build == null ? null : directChild(build, "plugins");
            NodeList plugins = pluginContainer == null ? null : pluginContainer.getChildNodes();
            for (int i = 0; plugins != null && i < plugins.getLength(); i++) {
                if (plugins.item(i) instanceof Element plugin && "plugin".equals(plugin.getTagName())) {
                    String artifact = childText(plugin, "artifactId");
                    String version = childText(plugin, "version");
                    if (artifact != null) {
                        tools.add(tool(artifact, version, relative, "build plugin"));
                    }
                }
            }
        } catch (ParserConfigurationException | SAXException | IOException | RuntimeException e) {
            warnings.add("No se pudo analizar " + relative + " como XML Maven.");
        }
    }

    private void parseGradle(Path root, Path buildFile, String relative, List<Map<String, Object>> dependencies,
            List<Map<String, Object>> modules, List<Map<String, Object>> tools, List<String> warnings) {
        String content = readFile(buildFile, relative, warnings);
        Matcher matcher = GRADLE_DEPENDENCY.matcher(content);
        while (matcher.find() && dependencies.size() < MAX_DEPENDENCIES) {
            String configuration = matcher.group(1);
            String version = matcher.group(4);
            String scope = configuration.toLowerCase(Locale.ROOT).startsWith("test")
                    || "developmentOnly".equals(configuration) ? "DEVELOPMENT" : "PRODUCTION";
            dependencies.add(dependency(matcher.group(2) + ":" + matcher.group(3),
                    version, scope, "Gradle", relative, configuration));
        }
        matcher = GRADLE_ALIAS.matcher(content);
        while (matcher.find() && dependencies.size() < MAX_DEPENDENCIES) {
            String configuration = matcher.group(1);
            dependencies.add(dependency("libs." + matcher.group(2), null,
                    configuration.toLowerCase(Locale.ROOT).startsWith("test") ? "DEVELOPMENT" : "PRODUCTION",
                    "Gradle", relative, configuration));
        }
        tools.add(tool("Gradle", null, relative, "build system"));
        Matcher pluginMatcher = Pattern.compile(
                "(?m)\\bid\\s*\\(?\\s*['\"]([^'\"]+)['\"]\\s*\\)?\\s+version\\s+['\"]([^'\"]+)['\"]")
                .matcher(content);
        while (pluginMatcher.find()) {
            tools.add(tool(pluginMatcher.group(1), pluginMatcher.group(2), relative, "Gradle plugin"));
        }
        Matcher includes = GRADLE_INCLUDE.matcher(content);
        while (includes.find() && modules.size() < MAX_MODULES) {
            Matcher pathMatcher = Pattern.compile("['\"]:([^'\"]+)['\"]").matcher(includes.group(1));
            while (pathMatcher.find() && modules.size() < MAX_MODULES) {
                String path = pathMatcher.group(1).replace(':', '/');
                modules.add(module(path, "GRADLE_MODULE", relative, "include"));
            }
        }
        Matcher wrapper = Pattern.compile("gradle-([0-9][A-Za-z0-9.+-]*)-(?:bin|all)\\.zip")
                .matcher(readSibling(root, "gradle/wrapper/gradle-wrapper.properties", warnings));
        if (wrapper.find()) {
            tools.add(tool("Gradle Wrapper", wrapper.group(1),
                    "gradle/wrapper/gradle-wrapper.properties", "wrapper version"));
        }
        if (content.isBlank() && !Files.isReadable(buildFile)) {
            warnings.add("No se pudo leer " + relative + ".");
        }
    }

    private void parseNode(Path root, String relative, List<Map<String, Object>> dependencies,
            List<Map<String, Object>> modules, List<Map<String, Object>> tools, List<String> warnings) {
        try {
            Path file = root.resolve(relative);
            if (!isWithinReadLimit(file, MAX_JSON_FILE_BYTES, relative, warnings)) {
                return;
            }
            JsonNode manifest = JSON.readTree(file.toFile());
            addJsonDependencies(manifest.path("dependencies"), "PRODUCTION", "npm", relative, dependencies);
            addJsonDependencies(manifest.path("devDependencies"), "DEVELOPMENT", "npm", relative, dependencies);
            addJsonDependencies(manifest.path("optionalDependencies"), "PRODUCTION", "npm", relative, dependencies);
            addJsonDependencies(manifest.path("peerDependencies"), "PRODUCTION", "npm", relative, dependencies);
            JsonNode workspaces = manifest.path("workspaces");
            JsonNode workspaceEntries = workspaces.isArray() ? workspaces : workspaces.path("packages");
            if (workspaceEntries.isArray()) {
                for (JsonNode workspace : workspaceEntries) {
                    String path = workspace.asText().replace("*", "").replaceAll("/+$", "");
                    if (!path.isBlank()) {
                        String normalized = normalizeRelative(parentPath(relative), path);
                        if (!normalized.isBlank()) {
                            modules.add(module(normalized, "NPM_WORKSPACE", relative, "workspaces"));
                        }
                    }
                }
            }
            tools.add(tool("npm", null, relative, "package manager"));
            addPackageManagerFromLock(root, relative, tools);
            parseNpmLock(root, relative, dependencies, warnings);
        } catch (IOException e) {
            warnings.add("No se pudo analizar " + relative + " como JSON.");
        }
    }

    private void addPackageManagerFromLock(Path root, String relative, List<Map<String, Object>> tools) {
        Path lock = root.resolve(parentPath(relative)).resolve("package-lock.json");
        if (Files.isRegularFile(lock)) {
            tools.add(tool("npm", lockVersion(lock), relative(root, lock), "lockfile"));
            return;
        }
        for (String name : List.of("pnpm-lock.yaml", "yarn.lock", "bun.lockb", "bun.lock")) {
            Path file = root.resolve(parentPath(relative)).resolve(name);
            if (Files.isRegularFile(file)) {
                tools.add(tool(name.startsWith("pnpm") ? "pnpm" : name.startsWith("yarn") ? "Yarn" : "Bun",
                        null, relative(root, file), "lockfile"));
            }
        }
    }

    private String lockVersion(Path lock) {
        try {
            return JSON.readTree(lock.toFile()).path("lockfileVersion").asText(null);
        } catch (IOException e) {
            return null;
        }
    }

    private void parseNpmLock(Path root, String packageManifest, List<Map<String, Object>> dependencies,
            List<String> warnings) {
        Path lock = root.resolve(parentPath(packageManifest)).resolve("package-lock.json");
        if (!Files.isRegularFile(lock)) {
            return;
        }
        try {
            if (!isWithinReadLimit(lock, MAX_JSON_FILE_BYTES, relative(root, lock), warnings)) {
                return;
            }
            JsonNode lockRoot = JSON.readTree(lock.toFile());
            JsonNode packages = lockRoot.path("packages");
            if (packages.isObject()) {
                JsonNode directManifest = JSON.readTree(root.resolve(packageManifest).toFile());
                Set<String> directProduction = new LinkedHashSet<>();
                Set<String> directDevelopment = new LinkedHashSet<>();
                for (String section : List.of("dependencies", "optionalDependencies", "peerDependencies")) {
                    directManifest.path(section).fieldNames().forEachRemaining(directProduction::add);
                }
                directManifest.path("devDependencies").fieldNames().forEachRemaining(directDevelopment::add);
                packages.fields().forEachRemaining(entry -> {
                    String path = entry.getKey();
                    if (path.isBlank() || dependencies.size() >= MAX_DEPENDENCIES) {
                        return;
                    }
                    String name = nodePackageName(path);
                    if (name == null) {
                        return;
                    }
                    JsonNode value = entry.getValue();
                    boolean directProd = directProduction.contains(name);
                    boolean directDev = directDevelopment.contains(name);
                    String scope = directProd ? "PRODUCTION" : directDev ? "DEVELOPMENT" : "TRANSITIVE";
                    dependencies.add(dependency(name, value.path("version").asText(null), scope,
                            "npm", relative(root, lock), directProd || directDev
                                    ? "package-lock direct package" : "package-lock package"));
                });
            }
        } catch (IOException e) {
            warnings.add("No se pudo analizar package-lock.json.");
        }
    }

    private String nodePackageName(String packagePath) {
        String normalized = packagePath.replace('\\', '/');
        int index = normalized.lastIndexOf("node_modules/");
        return index < 0 ? null : normalized.substring(index + "node_modules/".length());
    }

    private void parseComposer(Path root, String relative, List<Map<String, Object>> dependencies,
            List<Map<String, Object>> modules, List<Map<String, Object>> tools, List<String> warnings) {
        try {
            Path file = root.resolve(relative);
            if (!isWithinReadLimit(file, MAX_JSON_FILE_BYTES, relative, warnings)) {
                return;
            }
            JsonNode manifest = JSON.readTree(file.toFile());
            addJsonDependencies(manifest.path("require"), "PRODUCTION", "Composer", relative, dependencies);
            addJsonDependencies(manifest.path("require-dev"), "DEVELOPMENT", "Composer", relative, dependencies);
            tools.add(tool("Composer", null, relative, "package manager"));
            JsonNode packages = manifest.path("repositories");
            if (packages.isArray()) {
                for (JsonNode repository : packages) {
                    if ("path".equals(repository.path("type").asText())) {
                        String path = repository.path("url").asText();
                        if (!path.isBlank()) {
                            String normalized = normalizeRelative(parentPath(relative), path);
                            if (!normalized.isBlank()) {
                                modules.add(module(normalized, "COMPOSER_PATH_REPOSITORY", relative, "repositories.url"));
                            }
                        }
                    }
                }
            }
            Set<String> directProduction = new LinkedHashSet<>();
            Set<String> directDevelopment = new LinkedHashSet<>();
            manifest.path("require").fieldNames().forEachRemaining(directProduction::add);
            manifest.path("require-dev").fieldNames().forEachRemaining(directDevelopment::add);
            parseComposerLock(root, relative, directProduction, directDevelopment, dependencies, warnings);
        } catch (IOException e) {
            warnings.add("No se pudo analizar " + relative + " como JSON.");
        }
    }

    private void parseComposerLock(Path root, String manifest, Set<String> directProduction,
            Set<String> directDevelopment, List<Map<String, Object>> dependencies, List<String> warnings) {
        Path lock = root.resolve(parentPath(manifest)).resolve("composer.lock");
        if (!Files.isRegularFile(lock)) {
            return;
        }
        try {
            if (!isWithinReadLimit(lock, MAX_JSON_FILE_BYTES, relative(root, lock), warnings)) {
                return;
            }
            JsonNode lockRoot = JSON.readTree(lock.toFile());
            for (String section : List.of("packages", "packages-dev")) {
                JsonNode packages = lockRoot.path(section);
                if (!packages.isArray()) {
                    continue;
                }
                for (JsonNode packageNode : packages) {
                    if (dependencies.size() >= MAX_DEPENDENCIES) {
                        return;
                    }
                    String name = packageNode.path("name").asText("unknown");
                    String scope = directProduction.contains(name) ? "PRODUCTION"
                            : directDevelopment.contains(name) ? "DEVELOPMENT" : "TRANSITIVE";
                    dependencies.add(dependency(
                            name,
                            packageNode.path("version").asText(null),
                            scope,
                            "Composer", relative(root, lock), "composer.lock " + section));
                }
            }
        } catch (IOException e) {
            warnings.add("No se pudo analizar composer.lock.");
        }
    }

    private void parsePyProject(Path file, String relative, List<Map<String, Object>> dependencies,
            List<Map<String, Object>> modules, List<Map<String, Object>> tools, List<String> warnings) {
        String content = readFile(file, relative, warnings);
        List<String> lines = content.lines().toList();
        String section = "";
        for (int index = 0; index < lines.size() && dependencies.size() < MAX_DEPENDENCIES; index++) {
            String line = lines.get(index).trim();
            Matcher sectionMatcher = Pattern.compile("^\\[([^]]+)]$").matcher(line);
            if (sectionMatcher.matches()) {
                section = sectionMatcher.group(1).toLowerCase(Locale.ROOT);
                continue;
            }
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            if ("project".equals(section) && line.matches("^dependencies\\s*=\\s*\\[.*")) {
                index = addTomlArrayDependencies(lines, index, "PRODUCTION", relative, dependencies);
            } else if (section.startsWith("project.optional-dependencies.")
                    && line.matches("^[A-Za-z0-9_.-]+\\s*=\\s*\\[.*")) {
                index = addTomlArrayDependencies(lines, index, "PRODUCTION", relative, dependencies);
            } else if ("tool.poetry.dependencies".equals(section)
                    || "tool.poetry.dev-dependencies".equals(section)
                    || section.matches("tool\\.poetry\\.group\\.[^.]+\\.dependencies")) {
                Matcher dependencyMatcher = Pattern.compile(
                        "^[\"']?([A-Za-z0-9_.-]+)[\"']?\\s*=\\s*(.+?)(?:\\s+#.*)?$")
                        .matcher(line);
                if (dependencyMatcher.matches() && !"python".equalsIgnoreCase(dependencyMatcher.group(1))) {
                    String name = dependencyMatcher.group(1);
                    String rawVersion = dependencyMatcher.group(2).trim();
                    Matcher localPath = Pattern.compile("path\\s*=\\s*[\"']([^\"']+)[\"']")
                            .matcher(rawVersion);
                    if (localPath.find()) {
                        String normalized = normalizeRelative(parentPath(relative), localPath.group(1));
                        if (!normalized.isBlank()) {
                            modules.add(module(normalized, "PYTHON_LOCAL_DEPENDENCY", relative, name));
                        }
                    } else {
                        String scope = section.equals("tool.poetry.dependencies")
                                || section.equals("tool.poetry.group.main.dependencies")
                                ? "PRODUCTION" : "DEVELOPMENT";
                        Matcher version = Pattern.compile("version\\s*=\\s*[\"']([^\"']+)[\"']")
                                .matcher(rawVersion);
                        String declaredVersion = version.find()
                                ? version.group(1)
                                : rawVersion.replaceAll("^[\"']|[\"']$", "");
                        dependencies.add(dependency(name, declaredVersion,
                                scope, "Poetry", relative, "project dependency"));
                    }
                }
            }
        }
        if (content.contains("[tool.poetry")) {
            tools.add(tool("Poetry", null, relative, "pyproject build/dependency metadata"));
        } else {
            tools.add(tool("Python build metadata", null, relative, "pyproject.toml"));
        }
    }

    private int addTomlArrayDependencies(List<String> lines, int startIndex, String scope, String file,
            List<Map<String, Object>> dependencies) {
        StringBuilder array = new StringBuilder(lines.get(startIndex));
        int index = startIndex;
        while (!array.toString().contains("]") && index + 1 < lines.size()) {
            array.append('\n').append(lines.get(++index));
        }
        Matcher item = Pattern.compile("[\"']([^\"']+)[\"']").matcher(array);
        while (item.find() && dependencies.size() < MAX_DEPENDENCIES) {
            Matcher requirement = REQUIREMENT.matcher(item.group(1));
            if (requirement.find()) {
                dependencies.add(dependency(requirement.group(1), requirement.group(2) == null
                        ? null : requirement.group(2).replaceAll("\\s+", ""),
                        scope, "pyproject", file, "project dependency"));
            }
        }
        return index;
    }

    private void parseRequirements(Path file, String relative, List<Map<String, Object>> dependencies,
            List<Map<String, Object>> tools, List<String> warnings) {
        int lineNumber = 0;
        for (String line : readFile(file, relative, warnings).lines().toList()) {
            lineNumber++;
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("-")) {
                continue;
            }
            Matcher matcher = REQUIREMENT.matcher(trimmed);
            if (matcher.find() && dependencies.size() < MAX_DEPENDENCIES) {
                dependencies.add(dependency(matcher.group(1), matcher.group(2) == null
                        ? null : matcher.group(2).replaceAll("\\s+", ""),
                        "PRODUCTION", "pip", relative, "requirements line " + lineNumber));
            }
        }
        tools.add(tool("pip", null, relative, "requirements manifest"));
    }

    private void parsePipfile(Path file, String relative, List<Map<String, Object>> dependencies,
            List<Map<String, Object>> tools, List<String> warnings) {
        String section = "";
        for (String line : readFile(file, relative, warnings).lines().toList()) {
            String trimmed = line.trim();
            Matcher sectionMatcher = Pattern.compile("^\\[([^]]+)]$").matcher(trimmed);
            if (sectionMatcher.matches()) {
                section = sectionMatcher.group(1).toLowerCase(Locale.ROOT);
                continue;
            }
            if (!section.equals("packages") && !section.equals("dev-packages")) {
                continue;
            }
            Matcher dependencyMatcher = Pattern.compile(
                    "^[\"']?([A-Za-z0-9_.-]+)[\"']?\\s*=\\s*(.+?)(?:\\s+#.*)?$")
                    .matcher(trimmed);
            if (dependencyMatcher.matches() && dependencies.size() < MAX_DEPENDENCIES) {
                String version = dependencyMatcher.group(2).trim().replaceAll("^[\"']|[\"']$", "");
                dependencies.add(dependency(dependencyMatcher.group(1), version,
                        section.equals("packages") ? "PRODUCTION" : "DEVELOPMENT",
                        "Pipenv", relative, "Pipfile " + section));
            }
        }
        tools.add(tool("Pipenv", null, relative, "dependency manifest"));
    }

    private boolean isWithinReadLimit(Path file, long maxBytes, String label, List<String> warnings) {
        return ProjectFileReader.withinLimit(file, maxBytes, label, warnings);
    }

    private void addJsonDependencies(JsonNode object, String scope, String manager, String file,
            List<Map<String, Object>> dependencies) {
        if (!object.isObject()) {
            return;
        }
        object.fields().forEachRemaining(entry -> {
            if (dependencies.size() < MAX_DEPENDENCIES && entry.getValue().isTextual()) {
                dependencies.add(dependency(entry.getKey(), entry.getValue().asText(), scope,
                        manager, file, entry.getKey()));
            }
        });
    }

    private void collectBuildModules(Path root, List<Map<String, Object>> modules, List<String> warnings) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                private int found;

                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(root) && IGNORED_DIRS.contains(directory.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return found >= MAX_DISCOVERED_FILES ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    found++;
                    if (!attributes.isRegularFile() || modules.size() >= MAX_MODULES) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = file.getFileName().toString();
                    if (MANIFESTS.contains(name) && !file.getParent().equals(root)) {
                        String path = relative(root, file.getParent());
                        if (!path.isBlank()) {
                            modules.add(module(path, "BUILD_MODULE", relative(root, file), name));
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            warnings.add("No se pudo recorrer toda la estructura de módulos.");
        }
    }

    private void collectSourceAreas(Path root, List<Map<String, Object>> modules, List<String> warnings) {
        Set<String> recognized = Set.of(
                "frontend", "backend", "api", "web", "client", "server", "database", "db",
                "infra", "infrastructure", "services", "packages", "apps", "migrations", "routes",
                "domain", "application", "ports", "adapters", "controllers", "controller",
                "service", "repositories", "repository", "models", "views", "usecases",
                "use-cases", "entities", "internal", "cmd", "pkg");
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                private int visited;

                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (modules.size() >= MAX_MODULES || ++visited > 2_500) {
                        warnings.add("El mapa de áreas de código se limitó por el tamaño del proyecto.");
                        return FileVisitResult.TERMINATE;
                    }
                    if (!directory.equals(root) && (IGNORED_DIRS.contains(directory.getFileName().toString())
                            || directory.getFileName().toString().startsWith("."))) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    int depth = directory.getNameCount() - root.getNameCount();
                    if (depth > 0 && depth <= 9
                            && recognized.contains(directory.getFileName().toString().toLowerCase(Locale.ROOT))) {
                        String path = relative(root, directory);
                        modules.add(module(path, "SOURCE_AREA", path, "directory"));
                    }
                    return depth >= 9 ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            warnings.add("No se pudieron enumerar todas las áreas de código fuente.");
        }
    }

    private void collectDatabaseConfigurations(Path root, List<Map<String, Object>> databases,
            List<Map<String, Object>> configurations, List<String> warnings) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                private int visitedFiles;

                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(root) && IGNORED_DIRS.contains(directory.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    if (visitedFiles >= MAX_DISCOVERED_FILES) {
                        warnings.add("Se alcanzó el límite de archivos al buscar configuraciones de base de datos.");
                        return FileVisitResult.TERMINATE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (++visitedFiles > MAX_DISCOVERED_FILES) {
                        warnings.add("Se alcanzó el límite de archivos al buscar configuraciones de base de datos.");
                        return FileVisitResult.TERMINATE;
                    }
                    if (!attributes.isRegularFile() || !isDatabaseConfiguration(file)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String configuration = relative(root, file);
                    configurations.add(config(configuration, "database/runtime configuration"));
                    String content = readFile(file, configuration, warnings);
                    Matcher url = PROPERTY_DATABASE_URL.matcher(content);
                    while (url.find()) {
                        String type = databaseType(url.group(2));
                        if (type != null) {
                            databases.add(database(type, "CONFIGURED", configuration, url.group(1)));
                        }
                    }
                    Matcher driver = DATABASE_DRIVER_PROPERTY.matcher(content);
                    while (driver.find()) {
                        String type = databaseType(driver.group(2));
                        if (type != null) {
                            databases.add(database(type, "CONFIGURED", configuration, driver.group(1)));
                        }
                    }
                    if (file.getFileName().toString().endsWith(".yml")
                            || file.getFileName().toString().endsWith(".yaml")) {
                        for (String yamlUrl : yamlDatasourceUrls(content)) {
                            String type = databaseType(yamlUrl);
                            if (type != null) {
                                databases.add(database(type, "CONFIGURED", configuration, "spring.datasource.url"));
                            }
                        }
                    }
                    if (configuration.endsWith("schema.prisma")) {
                        Matcher provider = Pattern.compile("(?m)^\\s*provider\\s*=\\s*[\"']([^\"']+)[\"']")
                                .matcher(content);
                        if (provider.find()) {
                            String type = databaseType(provider.group(1));
                            if (type != null) {
                                databases.add(database(type, "CONFIGURED", configuration, "datasource provider"));
                            }
                        }
                        String configName = file.getFileName().toString();
                        if (configName.startsWith("ormconfig.") || configName.startsWith("knexfile.")) {
                            Matcher typeMatcher = DATABASE_TYPE_PROPERTY.matcher(content);
                            while (typeMatcher.find()) {
                                String type = databaseType(typeMatcher.group(1));
                                if (type != null) {
                                    databases.add(database(type, "CONFIGURED", configuration, typeMatcher.group(1)));
                                    break;
                                }
                            }
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            warnings.add("No se pudieron enumerar todas las configuraciones de base de datos.");
        }
    }

    private boolean isDatabaseConfiguration(Path file) {
        String name = file.getFileName().toString();
        return name.equals("application.properties") || name.equals("application.yml")
                || name.equals("application.yaml") || name.equals("ormconfig.json")
                || name.equals("knexfile.js") || name.equals("knexfile.ts")
                || name.equals("schema.prisma");
    }

    private List<String> yamlDatasourceUrls(String content) {
        List<String> values = new ArrayList<>();
        int datasourceIndent = -1;
        for (String line : content.split("\\R")) {
            Matcher matcher = YAML_KEY_VALUE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            int indentation = matcher.group(1).length();
            String key = matcher.group(2);
            if ("datasource".equals(key)) {
                datasourceIndent = indentation;
            } else if (datasourceIndent >= 0) {
                if (indentation <= datasourceIndent) {
                    datasourceIndent = -1;
                } else if ("url".equals(key)) {
                    String value = matcher.group(3).replaceAll("^[\"']|[\"']$", "").trim();
                    if (!value.isBlank()) {
                        values.add(value);
                    }
                }
            }
        }
        return values;
    }

    private void collectDatabaseDependencies(List<Map<String, Object>> dependencies,
            List<Map<String, Object>> databases) {
        for (Map<String, Object> dependency : dependencies) {
            String name = String.valueOf(dependency.get("name")).toLowerCase(Locale.ROOT);
            String databaseType = switch (name) {
                case "org.postgresql:postgresql", "pg", "pg-native", "psycopg", "psycopg2",
                        "psycopg-binary", "asyncpg", "npgsql" -> "PostgreSQL";
                case "mysql:mysql-connector-java", "com.mysql:mysql-connector-j", "mysql2",
                        "mysql-connector-python", "pymysql" -> "MySQL";
                case "org.mariadb.jdbc:mariadb-java-client", "mariadb" -> "MariaDB";
                case "org.xerial:sqlite-jdbc", "sqlite3", "better-sqlite3" -> "SQLite";
                case "org.mongodb:mongodb-driver-sync", "mongodb", "pymongo" -> "MongoDB";
                case "ioredis", "redis", "redis-py" -> "Redis";
                default -> databaseFromDriverName(name);
            };
            if (databaseType != null) {
                Object file = dependency.get("file");
                databases.add(database(databaseType, "DEPENDENCY", String.valueOf(file), String.valueOf(dependency.get("name"))));
            }
        }
    }

    private String databaseFromDriverName(String name) {
        Matcher matcher = SQL_DRIVER.matcher(name);
        return matcher.find() ? normalizeDatabaseName(matcher.group(1)) : null;
    }

    private void collectComposeServices(Path root, List<Map<String, Object>> services,
            List<Map<String, Object>> databases, List<Map<String, Object>> configurations,
            List<String> warnings) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                private int visitedFiles;

                @Override
                public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (!directory.equals(root) && IGNORED_DIRS.contains(directory.getFileName().toString())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return visitedFiles >= MAX_DISCOVERED_FILES
                            ? FileVisitResult.TERMINATE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (++visitedFiles > MAX_DISCOVERED_FILES) {
                        warnings.add("Se alcanzó el límite de archivos al buscar servicios Compose.");
                        return FileVisitResult.TERMINATE;
                    }
                    if (attributes.isRegularFile() && COMPOSE_FILES.contains(file.getFileName().toString())) {
                        parseComposeFile(root, file, services, databases, configurations, warnings);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            warnings.add("No se pudieron enumerar todos los archivos Compose.");
        }
    }

    private void parseComposeFile(Path root, Path compose, List<Map<String, Object>> services,
            List<Map<String, Object>> databases, List<Map<String, Object>> configurations,
            List<String> warnings) {
        String composeName = relative(root, compose);
        configurations.add(config(composeName, "service configuration"));
        try {
            if (Files.size(compose) > MAX_TEXT_FILE_BYTES) {
                warnings.add("Se omitió " + composeName + " porque supera el tamaño máximo de lectura.");
                return;
            }
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            options.setMaxAliasesForCollections(20);
            Object document = new Yaml(new SafeConstructor(options)).load(Files.readString(compose));
            if (!(document instanceof Map<?, ?> rootMap)
                    || !(rootMap.get("services") instanceof Map<?, ?> serviceMap)) {
                return;
            }
            for (var entry : serviceMap.entrySet()) {
                if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof Map<?, ?> definition)) {
                    continue;
                }
                String image = definition.get("image") instanceof String value ? value : null;
                Object declared = definition.get("depends_on");
                List<String> dependsOn = (declared instanceof Map<?, ?> dependencies ? dependencies.keySet().stream()
                        : declared instanceof List<?> dependencies ? dependencies.stream() : java.util.stream.Stream.empty())
                        .filter(String.class::isInstance).map(String.class::cast).distinct().toList();
                addComposeService(composeName, name, image, dependsOn, services, databases);
            }
        } catch (IOException | YAMLException e) {
            warnings.add("No se pudo leer " + composeName + ".");
        }
    }

    private void addComposeService(String composeFile, String serviceName, String image, List<String> dependsOn,
            List<Map<String, Object>> services, List<Map<String, Object>> databases) {
        String imageName = image == null ? null : image.substring(image.lastIndexOf('/') + 1);
        String type = imageName == null ? "SERVICE" : "CONTAINER";
        Map<String, Object> component = service(serviceName, type, sanitizeImage(image), composeFile);
        component.put("dependsOn", dependsOn);
        services.add(component);
        Matcher databaseMatch = DATABASE_IMAGE.matcher((serviceName + " " + (imageName == null ? "" : imageName)));
        if (databaseMatch.find()) {
            databases.add(database(
                    normalizeDatabaseName(databaseMatch.group(1)), "DECLARED", composeFile, "services." + serviceName));
        }
    }

    private void collectBuildTools(Path root, List<Map<String, Object>> tools,
            List<Map<String, Object>> configurations) {
        for (Map<String, Object> configuration : configurations) {
            if ("service configuration".equals(configuration.get("purpose"))) {
                tools.add(tool("Docker Compose", null, String.valueOf(configuration.get("file")),
                        "service orchestration"));
            }
        }
        if (Files.isRegularFile(root.resolve("Dockerfile"))) {
            tools.add(tool("Docker", null, "Dockerfile", "container build"));
        }
        if (Files.isRegularFile(root.resolve("Makefile"))) {
            tools.add(tool("Make", null, "Makefile", "build automation"));
        }
        if (Files.isRegularFile(root.resolve("justfile"))) {
            tools.add(tool("Just", null, "justfile", "build automation"));
        }
    }

    private String databaseType(String source) {
        if (source == null) {
            return null;
        }
        Matcher matcher = DATABASE_URL.matcher(source);
        return matcher.find() ? normalizeDatabaseName(matcher.group(1)) : null;
    }

    private String sanitizeImage(String image) {
        return image == null ? null : image.replaceAll("(?i)(^|//)[^/@\\s]+@", "$1[REDACTED]@");
    }

    private String normalizeDatabaseName(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "postgres", "postgresql", "pg" -> "PostgreSQL";
            case "mysql", "mysql2" -> "MySQL";
            case "mariadb" -> "MariaDB";
            case "mongodb", "mongo", "mongodb+srv" -> "MongoDB";
            case "sqlserver", "mssql" -> "SQL Server";
            case "sqlite" -> "SQLite";
            case "h2" -> "H2";
            default -> value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1);
        };
    }

    private String childText(Element element, String name) {
        Element child = directChild(element, name);
        if (child == null) {
            return null;
        }
        String value = child.getTextContent().trim();
        return value.isBlank() ? null : value;
    }

    private Element directChild(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child && name.equals(child.getTagName())) {
                return child;
            }
        }
        return null;
    }

    private Map<String, Object> dependency(String name, String version, String scope,
            String manager, String file, String property) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("version", normalizeVersion(version));
        value.put("scope", scope);
        value.put("manager", manager);
        value.put("file", file);
        value.put("property", property);
        return value;
    }

    private Map<String, Object> module(String path, String type, String file, String property) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", path.isBlank() ? "root" : Path.of(path).getFileName().toString());
        value.put("path", path);
        value.put("type", type);
        value.put("file", file);
        value.put("property", property);
        return value;
    }

    private Map<String, Object> tool(String name, String version, String file, String property) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("version", normalizeVersion(version));
        value.put("file", file);
        value.put("property", property);
        return value;
    }

    private Map<String, Object> database(String name, String status, String file, String property) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("status", status);
        value.put("file", file);
        value.put("property", property);
        return value;
    }

    private Map<String, Object> service(String name, String type, String image, String file) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("type", type);
        value.put("image", image);
        value.put("file", file);
        return value;
    }

    private Map<String, Object> config(String file, String purpose) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("file", file);
        value.put("purpose", purpose);
        return value;
    }

    private List<Map<String, Object>> uniqueByPath(List<Map<String, Object>> values) {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> value : values) {
            unique.putIfAbsent(String.valueOf(value.get("path")), value);
        }
        return List.copyOf(unique.values());
    }

    private List<Map<String, Object>> uniqueDependencies(List<Map<String, Object>> values) {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> value : values) {
            String key = value.get("manager") + ":" + value.get("name") + ":" + value.get("scope");
            Map<String, Object> existing = unique.get(key);
            if (existing == null || existing.get("version") == null
                    || String.valueOf(value.get("property")).contains("lock")) {
                unique.put(key, value);
            }
        }
        return unique.values().stream().limit(MAX_DEPENDENCIES).toList();
    }

    private List<Map<String, Object>> uniqueByNameAndFile(List<Map<String, Object>> values) {
        Map<String, Map<String, Object>> unique = new LinkedHashMap<>();
        for (Map<String, Object> value : values) {
            String key = value.get("name") + ":" + value.get("file");
            unique.putIfAbsent(key, value);
        }
        return List.copyOf(unique.values());
    }

    private String readFile(Path file, String label, List<String> warnings) {
        return ProjectFileReader.read(file, MAX_TEXT_FILE_BYTES, label, warnings);
    }

    private String readSibling(Path root, String relative, List<String> warnings) {
        try {
            Path file = root.resolve(relative);
            if (!Files.isRegularFile(file)) {
                return "";
            }
            if (!isWithinReadLimit(file, MAX_TEXT_FILE_BYTES, relative, warnings)) {
                return "";
            }
            return Files.readString(file);
        } catch (IOException e) {
            warnings.add("No se pudo leer " + relative + ".");
            return "";
        }
    }

    private boolean hasComposeFile(Path root) {
        return COMPOSE_FILES.stream().anyMatch(name -> Files.isRegularFile(root.resolve(name)));
    }

    private String normalizeVersion(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        return normalized.matches("\\$\\{[^}]+}") || normalized.equals("*") ? null : normalized;
    }

    private String parentPath(String path) {
        Path parent = Path.of(path).getParent();
        return parent == null ? "" : parent.toString().replace('\\', '/');
    }

    private String relative(Path root, Path file) {
        return root.relativize(file).toString().replace('\\', '/');
    }

    private String normalizeRelative(String parent, String child) {
        try {
            Path path = Path.of(parent.isBlank() ? child : parent + "/" + child).normalize();
            return path.startsWith("..") ? "" : path.toString().replace('\\', '/');
        } catch (java.nio.file.InvalidPathException e) {
            return "";
        }
    }
}
