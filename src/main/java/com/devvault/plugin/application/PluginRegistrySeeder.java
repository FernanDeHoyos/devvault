package com.devvault.plugin.application;

import com.devvault.discovery.plugin.*;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.devvault.plugin.domain.PluginDescriptor;
import com.devvault.plugin.infrastructure.PluginDescriptorRepository;

/**
 * Al arrancar, registra en plugin_descriptors cualquier TechnologyPlugin
 * que Spring haya detectado en el classpath pero que todavía no exista en
 * la tabla — así el Plugin System siempre refleja los plugins reales del
 * código, sin necesitar un registro manual. Si el plugin ya existía
 * (por ejemplo, el usuario lo deshabilitó desde la API), no se toca su
 * estado — el seeder solo agrega lo que falta.
 */

@Component 
public class PluginRegistrySeeder implements ApplicationRunner{

    private static final Logger log = LoggerFactory.getLogger(PluginRegistrySeeder.class);
    private final List<TechnologyPlugin> plugins;
    private final PluginDescriptorRepository repository;

    /**
     * Constructor del seeder de plugins.
     * @param plugins Lista de plugins
     * @param repository Repositorio de plugins
     */
    public PluginRegistrySeeder(List<TechnologyPlugin> plugins, PluginDescriptorRepository repository) {
        this.plugins = plugins;
        this.repository = repository;
    }
 
    /**
     * Registra los plugins en la base de datos.
     * @param args Argumentos de la aplicación
     */
    @Override
    public void run(ApplicationArguments args) {
        for (TechnologyPlugin plugin : plugins) {
            String name = plugin.pluginName();
            var existing = repository.findByName(name);
            if (existing.isEmpty()) {
                repository.save(new PluginDescriptor(name, plugin.targetMarkerFiles(), plugin.version()));
                log.info(">>> Plugin registrado: {} (marcador: {})", name, plugin.targetMarkerFiles());
            } else if (!existing.get().getTargetMarkerFiles().equals(plugin.targetMarkerFiles())) {
                // Refresh plugin metadata without changing a user's enabled/disabled setting.
                existing.get().updateTargetMarkerFiles(plugin.targetMarkerFiles());
                repository.save(existing.get());
            }
        }
    }
    
}
