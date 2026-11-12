package com.devvault.runtime.application;

import com.devvault.discovery.plugin.RunConfiguration;
import com.devvault.discovery.plugin.TechnologyPlugin;
import com.devvault.shared.api.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

@Component
public class LocalRunConfigurationResolver {
    private final ProjectRunRecipeLoader recipes;
    private final List<TechnologyPlugin> plugins;

    public LocalRunConfigurationResolver(ProjectRunRecipeLoader recipes, List<TechnologyPlugin> plugins) {
        this.recipes = recipes;
        this.plugins = plugins;
    }

    public RunConfiguration resolve(Path projectPath) {
        return recipes.load(projectPath).orElseGet(() -> plugins.stream()
                .sorted(Comparator.comparingInt(TechnologyPlugin::detectionPriority).reversed())
                .filter(plugin -> plugin.detect(projectPath).isPresent())
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No se pudo determinar la tecnología del proyecto: " + projectPath))
                .getRunConfiguration(projectPath)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "La tecnología detectada no tiene una configuración de ejecución local: " + projectPath)));
    }
}
