package com.devvault.discovery.plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
        try {
            root = objectMapper.readTree(packageJson.toFile());
        } catch (Exception e) {
            return Optional.empty();
        }

        boolean isReact = hasDependency(root, "react");
        boolean isTypescript = Files.exists(projectDir.resolve("tsconfig.json"));
        boolean isNest = hasDependency(root, "@nestjs/core");
        boolean isFastify = hasDependency(root, "fastify");
        boolean isExpress = hasDependency(root, "express");

        String language = isTypescript ? "TypeScript" : "JavaScript";
        String version = isReact ? extractDependencyVersion(root, "react") : extractNodeEngineVersion(root);
        String framework = isReact ? "React" : isNest ? "NestJS" : isFastify ? "Fastify" : isExpress ? "Express" : "Node";
        List<String> technologies = new ArrayList<>();
        if (isNest) technologies.add("NestJS");
        if (isFastify) technologies.add("Fastify");
        if (isExpress) technologies.add("Express");
        Map<String, Object> markers = new LinkedHashMap<>();
        markers.put("marker", "package.json");
        markers.put("reactDetected", isReact);
        markers.put("typescriptDetected", isTypescript);
        markers.put("technologies", technologies.stream().distinct().toList());

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
                                List.of("npm.cmd", "run", "dev"),
                                5174));
            }
            if (scripts.has("start")) {
                return Optional.of(
                        new RunConfiguration(
                                "app",
                                List.of("npm.cmd", "start"),
                                3000));
            }
        } catch (Exception e) {
            return Optional.empty();
        }

        return Optional.empty();
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
