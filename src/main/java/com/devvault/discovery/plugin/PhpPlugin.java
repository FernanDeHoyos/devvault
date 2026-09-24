package com.devvault.discovery.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Detects Composer/PHP projects, Laravel, and Filament without executing project code. */
@Component
public class PhpPlugin implements TechnologyPlugin {

    private static final int LARAVEL_PORT = 8000;
    private static final int PHP_SERVER_PORT = 8001;
    private static final Set<String> PHP_ENTRY_DIRECTORIES = Set.of(
            "public", "src", "app", "vendor", "tests", "routes", "config", "resources", "storage", "bootstrap");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String pluginName() {
        return "php-composer-detector";
    }

    @Override
    public String targetMarkerFiles() {
        return "composer.json, composer.lock, artisan, index.php";
    }

    @Override
    public int detectionPriority() {
        return 20;
    }

    @Override
    public Optional<DetectionResult> detect(Path projectDir) {
        Path composerFile = projectDir.resolve("composer.json");
        Path artisanFile = projectDir.resolve("artisan");
        boolean hasComposer = Files.isRegularFile(composerFile);
        boolean hasLaravelEntrypoint = Files.isRegularFile(artisanFile)
                && Files.isRegularFile(projectDir.resolve("bootstrap/app.php"));

        if (!hasComposer && !hasLaravelEntrypoint && !hasStandalonePhpEntrypoint(projectDir)) {
            return Optional.empty();
        }

        JsonNode manifest = null;
        boolean manifestParsed = false;
        if (hasComposer) {
            try {
                manifest = objectMapper.readTree(composerFile.toFile());
                manifestParsed = manifest != null && manifest.isObject();
            } catch (IOException ignored) {
                // The manifest itself is still enough to recognize an incomplete PHP project.
            }
        }

        Map<String, String> packages = new LinkedHashMap<>();
        collectManifestPackages(manifest, packages);
        boolean lockDetected = collectLockedPackages(projectDir.resolve("composer.lock"), packages);

        boolean laravelDetected = packages.containsKey("laravel/framework") || hasLaravelEntrypoint;
        List<String> filamentPackages = packages.keySet().stream()
                .filter(name -> name.equals("filament/filament") || name.startsWith("filament/"))
                .sorted()
                .toList();
        Map<String, String> detectedPackages = new LinkedHashMap<>();
        packages.forEach((name, packageVersion) -> {
            if ("laravel/framework".equals(name) || name.startsWith("filament/")) {
                detectedPackages.put(name, packageVersion);
            }
        });
        List<String> technologies = filamentPackages.isEmpty() ? List.of() : List.of("Filament");
        String phpConstraint = text(manifest == null ? null : manifest.path("require").path("php"));
        String framework = laravelDetected ? "Laravel" : "PHP";
        String version = laravelDetected
                ? packages.get("laravel/framework")
                : phpConstraint;

        Map<String, Object> markers = new LinkedHashMap<>();
        markers.put("marker", hasComposer ? "composer.json"
                : hasLaravelEntrypoint ? "artisan"
                        : Files.isRegularFile(projectDir.resolve("public/index.php")) ? "public/index.php" : "index.php");
        markers.put("projectType", hasComposer ? "COMPOSER" : laravelDetected ? "LARAVEL" : "STANDALONE_PHP");
        markers.put("composerManifestParsed", manifestParsed);
        markers.put("composerLockDetected", lockDetected);
        markers.put("laravelDetected", laravelDetected);
        markers.put("laravelReady", laravelDetected
                && Files.isRegularFile(artisanFile)
                && Files.isRegularFile(projectDir.resolve("bootstrap/app.php"))
                && Files.isRegularFile(projectDir.resolve("vendor/autoload.php")));
        if (phpConstraint != null) {
            markers.put("phpVersionConstraint", phpConstraint);
        }
        markers.put("technologies", technologies);
        markers.put("technologyPackages", filamentPackages);
        markers.put("detectedPackages", detectedPackages);

        return Optional.of(new DetectionResult("PHP", framework, version, markers));
    }

    @Override
    public Optional<RunConfiguration> getRunConfiguration(Path projectDir) {
        DetectionResult detection = detect(projectDir).orElse(null);
        if (detection == null || !isPhpCliAvailable(projectDir)) {
            return Optional.empty();
        }

        if ("Laravel".equals(detection.framework())) {
            boolean ready = Files.isRegularFile(projectDir.resolve("artisan"))
                    && Files.isRegularFile(projectDir.resolve("bootstrap/app.php"))
                    && Files.isRegularFile(projectDir.resolve("vendor/autoload.php"));
            if (!ready) {
                return Optional.empty();
            }
            return Optional.of(new RunConfiguration(
                    "app",
                    List.of("php", "artisan", "serve", "--host=127.0.0.1", "--port=" + LARAVEL_PORT),
                    LARAVEL_PORT));
        }

        if (Files.isRegularFile(projectDir.resolve("public/index.php"))) {
            return Optional.of(new RunConfiguration(
                    "app",
                    List.of("php", "-S", "127.0.0.1:" + PHP_SERVER_PORT, "-t", "public"),
                    PHP_SERVER_PORT));
        }
        if (Files.isRegularFile(projectDir.resolve("index.php"))) {
            return Optional.of(new RunConfiguration(
                    "app",
                    List.of("php", "-S", "127.0.0.1:" + PHP_SERVER_PORT),
                    PHP_SERVER_PORT));
        }
        return Optional.empty();
    }

    private boolean hasStandalonePhpEntrypoint(Path projectDir) {
        String directoryName = projectDir.getFileName() == null
                ? ""
                : projectDir.getFileName().toString().toLowerCase();
        if (PHP_ENTRY_DIRECTORIES.contains(directoryName)) {
            return false;
        }
        return Files.isRegularFile(projectDir.resolve("index.php"))
                || Files.isRegularFile(projectDir.resolve("public/index.php"));
    }

    private void collectManifestPackages(JsonNode manifest, Map<String, String> packages) {
        if (manifest == null || !manifest.isObject()) {
            return;
        }
        collectPackageMap(manifest.path("require"), packages);
        collectPackageMap(manifest.path("require-dev"), packages);
    }

    private void collectPackageMap(JsonNode packageMap, Map<String, String> packages) {
        if (!packageMap.isObject()) {
            return;
        }
        packageMap.fields().forEachRemaining(entry -> {
            if (!"php".equals(entry.getKey()) && entry.getValue().isTextual()) {
                packages.put(entry.getKey(), entry.getValue().asText());
            }
        });
    }

    private boolean collectLockedPackages(Path lockFile, Map<String, String> packages) {
        if (!Files.isRegularFile(lockFile)) {
            return false;
        }
        try {
            JsonNode lock = objectMapper.readTree(lockFile.toFile());
            collectLockList(lock.path("packages"), packages);
            collectLockList(lock.path("packages-dev"), packages);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private void collectLockList(JsonNode packageList, Map<String, String> packages) {
        if (!packageList.isArray()) {
            return;
        }
        for (JsonNode lockedPackage : packageList) {
            String name = text(lockedPackage.path("name"));
            String version = text(lockedPackage.path("version"));
            if (name != null && version != null) {
                packages.put(name, version);
            }
        }
    }

    private String text(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull() || !value.isValueNode()) {
            return null;
        }
        String result = value.asText().trim();
        return result.isEmpty() ? null : result;
    }

    /** Runs only `php --version` after the user explicitly requests project startup. */
    private boolean isPhpCliAvailable(Path projectDir) {
        Process process = null;
        try {
            process = new ProcessBuilder("php", "--version")
                    .directory(projectDir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(3, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
