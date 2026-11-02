package com.devvault.discovery.plugin;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class ReactNodePlugin implements TechnologyPlugin {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String pluginName() {
        return "react-node-detector";
    }

    @Override
    public String targetMarkerFiles() {
        return "package.json";
    }

    @Override
    public int detectionPriority() {
        return 10;
    }

    /**
     * Detecta la tecnología del proyecto.
     * 
     * @param projectDir Directorio del proyecto
     * @return Resultado de la detección si se detecta tecnología, Optional.empty()
     *         en caso contrario
     */
    @Override
    public Optional<DetectionResult> detect(Path projectDir) {
        Path packageJson = projectDir.resolve("package.json");
        if (!packageJson.toFile().exists()) {
            return Optional.empty();
        }
        JsonNode root;
        String warning = null;
        try {
            root = objectMapper.readTree(packageJson.toFile());
        } catch (java.io.IOException e) {
            root = objectMapper.createObjectNode();
            warning = "No se pudo interpretar package.json; solo se mostrarán detecciones respaldadas por otros archivos.";
        }

        boolean isReact = hasDependency(root, "react");
        boolean isTypescript = Files.exists(projectDir.resolve("tsconfig.json"));
        boolean isNest = hasDependency(root, "@nestjs/core");
        boolean isFastify = hasDependency(root, "fastify");
        boolean isExpress = hasDependency(root, "express");
        boolean isVue = hasDependency(root, "vue");
        boolean isAngular = hasDependency(root, "@angular/core");
        String marker = "package.json";

        String language = isTypescript ? "TypeScript" : "JavaScript";
        String framework = isReact ? "React" : isVue ? "Vue" : isAngular ? "Angular"
                : isNest ? "NestJS" : isFastify ? "Fastify" : isExpress ? "Express" : "Node.js";
        String version = isReact ? extractDependencyVersion(root, "react")
                : isVue ? extractDependencyVersion(root, "vue")
                        : isAngular ? extractDependencyVersion(root, "@angular/core")
                                : isNest ? extractDependencyVersion(root, "@nestjs/core")
                                        : isFastify ? extractDependencyVersion(root, "fastify")
                                                : isExpress ? extractDependencyVersion(root, "express")
                                                        : extractNodeEngineVersion(root);
        List<String> technologies = new ArrayList<>();
        if (isNest) technologies.add("NestJS");
        if (isFastify) technologies.add("Fastify");
        if (isExpress) technologies.add("Express");
        if (isVue) technologies.add("Vue");
        if (isAngular) technologies.add("Angular");
        List<Map<String, Object>> stackFindings = new ArrayList<>();
        String languageEvidence = isTypescript ? "tsconfig.json" : marker;
        stackFindings.add(TechnologyEvidence.direct(
                language, "LANGUAGE", null, languageEvidence, isTypescript ? "TypeScript configuration" : "package.json",
                isTypescript ? "tsconfig.json" : "JavaScript project manifest", true));
        if (isReact) addDependencyFinding(stackFindings, root, "React", "FRONTEND", "react");
        if (isVue) addDependencyFinding(stackFindings, root, "Vue", "FRONTEND", "vue");
        if (isAngular) addDependencyFinding(stackFindings, root, "Angular", "FRONTEND", "@angular/core");
        if (isNest) addDependencyFinding(stackFindings, root, "NestJS", "BACKEND", "@nestjs/core");
        if (isFastify) addDependencyFinding(stackFindings, root, "Fastify", "BACKEND", "fastify");
        if (isExpress) addDependencyFinding(stackFindings, root, "Express", "BACKEND", "express");
        if (!isReact && !isVue && !isAngular && !isNest && !isFastify && !isExpress) {
            stackFindings.add(TechnologyEvidence.direct(
                    "Node.js", "BACKEND", extractNodeEngineVersion(root), marker, "engines.node",
                    extractNodeEngineVersion(root), true));
        }
        addBuildToolFinding(stackFindings, root, "Vite", "vite");
        addBuildToolFinding(stackFindings, root, "Webpack", "webpack");
        if (hasDependency(root, "next")) {
            addDependencyFinding(stackFindings, root, "Next.js", "FRONTEND", "next");
        }
        Map<String, Object> markers = new LinkedHashMap<>();
        markers.put("marker", marker);
        markers.put("reactDetected", isReact);
        markers.put("typescriptDetected", isTypescript);
        markers.put("technologies", technologies.stream().distinct().toList());
        markers.put("stackFindings", stackFindings);
        if (warning != null) {
            markers.put("warnings", List.of(warning));
        }

        return Optional.of(new DetectionResult(
                language,
                framework,
                version,
                markers));
    }

    /**
     * Obtiene la configuración de ejecución del proyecto.
     * 
     * @param projectDir Directorio del proyecto
     * @return Configuración de ejecución si se encuentra, Optional.empty() en caso
     *         contrario
     */
    @Override
    public Optional<RunConfiguration> getRunConfiguration(Path projectDir) {

        Path packageJson = projectDir.resolve("package.json");
        if (!Files.exists(packageJson)) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(packageJson.toFile());
            JsonNode scripts = root.path("scripts");
            if (scripts.has("dev")) {
                return Optional.of(
                        new RunConfiguration(
                                "app",
                                npmCommand("run", "dev"),
                                5174));
            }
            if (scripts.has("start")) {
                return Optional.of(
                        new RunConfiguration(
                                "app",
                                npmCommand("start"),
                                3000));
            }
        } catch (Exception e) {
            return Optional.empty();
        }

        return Optional.empty();
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private List<String> npmCommand(String... arguments) {
        List<String> command = new ArrayList<>();
        if (isWindows()) {
            if (commandAvailableOnPath("npm.cmd")) {
                // ProcessBuilder no puede ejecutar shims .cmd directamente.
                command.addAll(List.of("cmd.exe", "/c", "npm.cmd"));
            } else if (commandAvailableOnPath("npm.exe")) {
                // Algunos gestores de Node para Windows publican un shim .exe en vez de npm.cmd.
                command.add("npm.exe");
            } else {
                command.addAll(List.of("cmd.exe", "/c", "npm.cmd"));
            }
        } else {
            command.add("npm");
        }
        command.addAll(List.of(arguments));
        return List.copyOf(command);
    }

    private boolean commandAvailableOnPath(String command) {
        String path = System.getenv("PATH");
        if (path == null || path.isBlank()) {
            return false;
        }
        for (String directory : path.split(Pattern.quote(File.pathSeparator))) {
            if (!directory.isBlank()) {
                Path candidate = Path.of(directory.replace("\"", "")).resolve(command);
                if (Files.isRegularFile(candidate)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Verifica si un paquete está instalado en el proyecto.
     * 
     * @param packageJson Nodo JSON del package.json
     * @param dependency  Nombre del paquete a verificar
     * @return true si el paquete está instalado, false en caso contrario
     */
    private boolean hasDependency(JsonNode packageJson, String dependency) {
        return packageJson.path(
                "dependencies")
                .has(dependency)
                || packageJson.path("devDependencies")
                        .has(dependency);
    }

    private void addDependencyFinding(List<Map<String, Object>> findings, JsonNode packageJson,
            String name, String category, String dependency) {
        findings.add(TechnologyEvidence.direct(
                name,
                category,
                extractDependencyVersion(packageJson, dependency),
                "package.json",
                dependency,
                extractDependencyVersion(packageJson, dependency),
                findings.stream().noneMatch(finding -> category.equals(finding.get("category"))
                        && Boolean.TRUE.equals(finding.get("primary")))));
    }

    private void addBuildToolFinding(List<Map<String, Object>> findings, JsonNode packageJson,
            String name, String dependency) {
        if (hasDependency(packageJson, dependency)) {
            addDependencyFinding(findings, packageJson, name, "TOOLS", dependency);
        }
    }

    /**
     * Extrae la versión de una dependencia del package.json.
     * 
     * @param packageJson Nodo JSON del package.json
     * @param dependency  Nombre de la dependencia
     * @return Versión de la dependencia si se encuentra, "desconocida" en caso
     *         contrario
     */
    private String extractDependencyVersion(JsonNode packageJson, String dependency) {
        JsonNode versionNode = packageJson.path("dependencies").path(dependency);
        if (versionNode.isMissingNode()) {
            versionNode = packageJson.path("devDependencies").path(dependency);
        }
        return versionNode.isMissingNode() ? "desconocida" : versionNode.asText();
    }

    /**
     * Extrae la versión de node del package.json.
     * 
     * @param packageJson Nodo JSON del package.json
     * @return Versión de node si se encuentra, "desconocida" en caso contrario
     */
    private String extractNodeEngineVersion(JsonNode packageJson) {
        JsonNode enginesNode = packageJson.path("engines").path("node");
        return enginesNode.isMissingNode() ? "desconocida" : enginesNode.asText();
    }

}
