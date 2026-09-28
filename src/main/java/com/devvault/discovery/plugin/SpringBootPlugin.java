package com.devvault.discovery.plugin;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** Detects Spring Boot projects built with Maven or Gradle, plus Java projects. */
@Component
public class SpringBootPlugin implements TechnologyPlugin {

    private static final Pattern MAVEN_BOOT_VERSION = Pattern.compile(
            "<artifactId>spring-boot-starter-parent</artifactId>\\s*<version>(.*?)</version>", Pattern.DOTALL);
    private static final Pattern MAVEN_BOOT_DEPENDENCY = Pattern.compile(
            "<groupId>org\\.springframework\\.boot</groupId>\\s*<artifactId>spring-boot-[^<]+</artifactId>", Pattern.DOTALL);
    private static final Pattern GRADLE_PLUGIN_VERSION = Pattern.compile(
            "\\bid\\s*\\(?\\s*['\"]org\\.springframework\\.boot['\"]\\s*\\)?\\s*version\\s*['\"]([^'\"]+)['\"]",
            Pattern.DOTALL);
    private static final Pattern GRADLE_LEGACY_PLUGIN_VERSION = Pattern.compile(
            "org\\.springframework\\.boot:spring-boot-gradle-plugin:([^'\"\\s)]+)");
    private static final Pattern GRADLE_BOOT_DEPENDENCY_VERSION = Pattern.compile(
            "org\\.springframework\\.boot:spring-boot-[\\w.-]+:([^'\"\\s)]+)");
    private static final Pattern GRADLE_BOOT_DEPENDENCY = Pattern.compile(
            "org\\.springframework\\.boot:spring-boot-[\\w.-]+|org\\.springframework\\.boot\\s*[:.]\\s*spring-boot-[\\w.-]+",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PROPERTIES_SERVER_PORT = Pattern.compile(
            "(?m)^\\s*server\\.port\\s*[=:]\\s*(?:\\$\\{[^}:]+:)?(\\d{1,5})\\}?.*$");
    private static final Pattern YAML_PORT = Pattern.compile(
            "^port\\s*:\\s*(?:['\"]?)(?:\\$\\{[^}:]+:)?(\\d{1,5})\\}?['\"]?\\s*(?:#.*)?$");
    private static final List<String> BUILD_FILES = List.of("pom.xml", "build.gradle", "build.gradle.kts");

    @Override
    public Optional<DetectionResult> detect(Path projectDir) {
        Map<String, Object> markers = new LinkedHashMap<>();
        boolean hasMaven = readIfPresent(projectDir.resolve("pom.xml"), markers);
        boolean hasGradleGroovy = readIfPresent(projectDir.resolve("build.gradle"), markers);
        boolean hasGradleKotlin = readIfPresent(projectDir.resolve("build.gradle.kts"), markers);
        if (!hasMaven && !hasGradleGroovy && !hasGradleKotlin) {
            return Optional.empty();
        }

        String maven = content(projectDir.resolve("pom.xml"));
        String gradle = content(projectDir.resolve("build.gradle")) + "\n"
                + content(projectDir.resolve("build.gradle.kts"));
        String allBuildContent = maven + "\n" + gradle;

        Matcher mavenVersionMatcher = MAVEN_BOOT_VERSION.matcher(maven);
        Matcher gradleVersionMatcher = GRADLE_PLUGIN_VERSION.matcher(gradle);
        Matcher legacyGradleVersionMatcher = GRADLE_LEGACY_PLUGIN_VERSION.matcher(gradle);
        Matcher gradleDependencyVersionMatcher = GRADLE_BOOT_DEPENDENCY_VERSION.matcher(gradle);
        String bootVersion = firstGroup(mavenVersionMatcher, gradleVersionMatcher, legacyGradleVersionMatcher,
                gradleDependencyVersionMatcher);

        boolean springBootDetected = mavenVersionMatcher.reset().find()
                || MAVEN_BOOT_DEPENDENCY.matcher(maven).find()
                || gradleVersionMatcher.reset().find()
                || GRADLE_LEGACY_PLUGIN_VERSION.matcher(gradle).find()
                || GRADLE_BOOT_DEPENDENCY.matcher(gradle).find();
        boolean javaProject = hasMaven || isJavaGradleProject(projectDir, gradle);
        if (!springBootDetected && !javaProject) {
            return Optional.empty();
        }

        String framework = springBootDetected ? "Spring Boot" : "Java";
        if (springBootDetected && (bootVersion == null || bootVersion.isBlank())) {
            bootVersion = "desconocida";
        }
        markers.put("marker", hasMaven ? "pom.xml" : hasGradleGroovy ? "build.gradle" : "build.gradle.kts");
        markers.put("springBootDetected", springBootDetected);
        markers.put("buildSystem", hasMaven ? "Maven" : "Gradle");
        markers.put("buildFiles", BUILD_FILES.stream().filter(name -> Files.isRegularFile(projectDir.resolve(name))).toList());
        return Optional.of(new DetectionResult("Java", framework, springBootDetected ? bootVersion : null, markers));
    }

    @Override
    public Optional<RunConfiguration> getRunConfiguration(Path projectDir) {
        DetectionResult detection = detect(projectDir).orElse(null);
        if (detection == null || !"Spring Boot".equals(detection.framework())) {
            return Optional.empty();
        }

        int configuredPort = configuredServerPort(projectDir);
        boolean useDynamicPort = configuredPort > 0 && !isPortAvailable(configuredPort);
        int runtimePort = useDynamicPort ? 0 : configuredPort;

        if (Files.isRegularFile(projectDir.resolve("pom.xml"))) {
            return Optional.of(new RunConfiguration("app", mavenCommand(projectDir, useDynamicPort), runtimePort, 120));
        }
        if (Files.isRegularFile(projectDir.resolve("build.gradle"))
                || Files.isRegularFile(projectDir.resolve("build.gradle.kts"))) {
            return Optional.of(new RunConfiguration("app", gradleCommand(projectDir, useDynamicPort), runtimePort, 120));
        }
        return Optional.empty();
    }

    private List<String> mavenCommand(Path projectDir, boolean useDynamicPort) {
        boolean windows = isWindows();
        String wrapper = windows ? "mvnw.cmd" : "mvnw";
        List<String> command = new ArrayList<>();
        if (Files.isRegularFile(projectDir.resolve(wrapper))) {
            if (windows) command.addAll(List.of("cmd.exe", "/c", wrapper));
            else command.addAll(List.of("sh", "./" + wrapper));
        } else if (windows) {
            command.addAll(List.of("cmd.exe", "/c", "mvn.cmd"));
        } else {
            command.add("mvn");
        }
        command.add("-B");
        if (useDynamicPort) command.add("-Dspring-boot.run.arguments=--server.port=0");
        command.add("spring-boot:run");
        return List.copyOf(command);
    }

    private List<String> gradleCommand(Path projectDir, boolean useDynamicPort) {
        boolean windows = isWindows();
        String wrapper = windows ? "gradlew.bat" : "gradlew";
        List<String> command = new ArrayList<>();
        if (Files.isRegularFile(projectDir.resolve(wrapper))) {
            if (windows) command.addAll(List.of("cmd.exe", "/c", wrapper));
            else command.addAll(List.of("sh", "./" + wrapper));
        } else if (windows) {
            command.addAll(List.of("cmd.exe", "/c", "gradle.bat"));
        } else {
            command.add("gradle");
        }
        command.add("--console=plain");
        command.add("bootRun");
        if (useDynamicPort) command.add("--args=--server.port=0");
        return List.copyOf(command);
    }

    private int configuredServerPort(Path projectDir) {
        Path resources = projectDir.resolve("src/main/resources");
        if (!Files.isDirectory(resources)) return 8080;
        try (var files = Files.list(resources)) {
            List<Path> configFiles = files
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().matches("application(?:-[^.]+)?\\.(?:properties|ya?ml)"))
                    .sorted(java.util.Comparator.comparingInt(path -> {
                        String name = path.getFileName().toString();
                        return name.equals("application.properties") ? 0
                                : name.equals("application.yml") ? 1
                                        : name.equals("application.yaml") ? 2 : 3;
                    }))
                    .toList();
            for (Path configFile : configFiles) {
                String content = Files.readString(configFile);
                Matcher propertiesMatcher = PROPERTIES_SERVER_PORT.matcher(content);
                if (propertiesMatcher.find()) return validPort(propertiesMatcher.group(1));
                Integer yamlPort = readYamlServerPort(content);
                if (yamlPort != null) return yamlPort;
            }
        } catch (IOException ignored) {
            // Fall back to Spring Boot's standard port when configuration is unreadable.
        }
        return 8080;
    }

    private Integer readYamlServerPort(String content) {
        boolean inServerSection = false;
        int serverIndent = -1;
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            if (trimmed.equals("---")) {
                inServerSection = false;
                serverIndent = -1;
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            if (!inServerSection && trimmed.matches("server\\s*:\\s*(?:#.*)?")) {
                inServerSection = true;
                serverIndent = indent;
                continue;
            }
            if (!inServerSection) continue;
            if (indent <= serverIndent) {
                inServerSection = false;
                serverIndent = -1;
                continue;
            }
            Matcher portMatcher = YAML_PORT.matcher(trimmed);
            if (portMatcher.matches()) return validPort(portMatcher.group(1));
        }
        return null;
    }

    private int validPort(String value) {
        int port = Integer.parseInt(value);
        return port >= 0 && port <= 65535 ? port : 8080;
    }

    private boolean isPortAvailable(int port) {
        if (port == 0) return true;
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("127.0.0.1", port));
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private boolean readIfPresent(Path file, Map<String, Object> markers) {
        if (!Files.isRegularFile(file)) return false;
        markers.put(file.getFileName().toString(), true);
        return true;
    }

    private String content(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : "";
        } catch (IOException ignored) {
            return "";
        }
    }

    private String firstGroup(Matcher... matchers) {
        for (Matcher matcher : matchers) {
            if (matcher.find()) return matcher.group(1).trim();
        }
        return null;
    }

    private boolean isJavaGradleProject(Path projectDir, String gradleContent) {
        boolean javaPlugin = Pattern.compile("\\bid\\s*\\(?\\s*['\"](?:java|java-library)['\"]|\\bapply\\s+plugin\\s*:\\s*['\"]java['\"]|(?s)plugins\\s*\\{\\s*java(?:\\s|})")
                .matcher(gradleContent).find();
        boolean kotlinJvmPlugin = Pattern.compile("org\\.jetbrains\\.kotlin\\.jvm|kotlin\\(['\"]jvm['\"]\\)")
                .matcher(gradleContent).find();
        return javaPlugin || kotlinJvmPlugin || Files.isDirectory(projectDir.resolve("src/main/java"))
                || Files.isDirectory(projectDir.resolve("src/main/kotlin"));
    }

    @Override
    public String pluginName() {
        return "spring-boot-detector";
    }

    @Override
    public String targetMarkerFiles() {
        return String.join(",", BUILD_FILES);
    }

    @Override
    public int detectionPriority() {
        return 30;
    }
}
