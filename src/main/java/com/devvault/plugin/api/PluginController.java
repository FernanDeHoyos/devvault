package com.devvault.plugin.api;

import com.devvault.discovery.plugin.TechnologyPluginCatalog;
import com.devvault.plugin.application.dto.PluginResponse;
import com.devvault.plugin.application.dto.UpdatePluginRequest;
import com.devvault.plugin.domain.PluginDescriptor;
import com.devvault.plugin.infrastructure.PluginDescriptorRepository;
import com.devvault.shared.api.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Controlador de plugins.
 */
@RestController
@RequestMapping("/api/v1/plugins")
public class PluginController {

    private final PluginDescriptorRepository repository;
    private final TechnologyPluginCatalog catalog;

    /**
     * Constructor del controlador de plugins.
     * @param repository Repositorio de plugins
     * @param catalog Catálogo de plugins disponibles
     */
    public PluginController(PluginDescriptorRepository repository, TechnologyPluginCatalog catalog) {
        this.repository = repository;
        this.catalog = catalog;
    }

    /**
     * Obtiene todos los plugins.
     * @return Lista de plugins
     */
    @GetMapping
    public List<PluginResponse> findAll() {
        return repository.findAll().stream()
                .filter(plugin -> catalog.contains(plugin.getName()))
                .map(PluginResponse::from)
                .toList();
    }

    /**
     * Actualiza un plugin.
     * @param id      ID del plugin
     * @param request Solicitud de actualización
     * @return Plugin actualizado
     */
    @PatchMapping("/{id}")
    public PluginResponse update(@PathVariable("id") UUID id, @RequestBody UpdatePluginRequest request) {
        PluginDescriptor descriptor = repository.findById(id)
                .filter(plugin -> catalog.contains(plugin.getName()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Plugin no encontrado: " + id));

        if (request.enabled() != null) {
            descriptor.setEnabled(request.enabled());
        }
        return PluginResponse.from(repository.save(descriptor));
    }
}
