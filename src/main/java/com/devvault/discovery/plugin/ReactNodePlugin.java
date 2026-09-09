package com.devvault.discovery.plugin;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class ReactNodePlugin implements TechnologyPlugin {

    private final ObjectMapper objectMapper = new ObjectMapper();
    
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

        String language = isTypescript ? "TypeScript" : "JavaScript";
        String version = isReact ? extractDependencyVersion(root, "react") : extractNodeEngineVersion(root);
        String framework = isReact ? "React" : "Node";
        
        return Optional.of(new DetectionResult(
            language, 
            framework, 
            version, 
            Map.of("marker", "package.json", "reactDetected", isReact, "typescriptDetected", isTypescript)));
    }

    private boolean hasDependency(JsonNode packageJson, String dependency) {
        return packageJson.path(
            "dependencies")
            .has(dependency) || packageJson.path("devDependencies")
            .has(dependency);
    }

    private String extractDependencyVersion(JsonNode packageJson, String dependency) {
        JsonNode versionNode = packageJson.path("dependencies").path(dependency);
        if (versionNode.isMissingNode()) {
            versionNode = packageJson.path("devDependencies").path(dependency);
        }
        return versionNode.isMissingNode() ? "desconocida" : versionNode.asText();
    }

    private String extractNodeEngineVersion(JsonNode packageJson) {
        JsonNode enginesNode = packageJson.path("engines").path("node");
        return enginesNode.isMissingNode() ? "desconocida" : enginesNode.asText();
    }
    
}
