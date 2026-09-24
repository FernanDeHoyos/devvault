package com.devvault.discovery.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
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
