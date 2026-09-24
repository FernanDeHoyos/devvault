package com.devvault.plugin.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Entidad que representa un descriptor de plugin.
 */
@Entity
@Table(name = "plugin_descriptors")
@Getter
@NoArgsConstructor
public class PluginDescriptor {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(unique = true, nullable = false, length = 80)
    private String name;

    @Column(name = "target_marker_files", nullable = false, length = 255)
    private String targetMarkerFiles;

    @Column(nullable = false, length = 20)
    private String version;

    @Column(nullable = false)
    private boolean enabled;

    /**
     * Constructor del descriptor de plugin.
     * 
     * @param name            Nombre del plugin
     * @param targetMarkerFiles Archivos marcadores
     * @param version         Versión del plugin
     */
    public PluginDescriptor(String name, String targetMarkerFiles, String version) {
        this.name = name;
        this.targetMarkerFiles = targetMarkerFiles;
        this.version = version;
        this.enabled = true; // habilitado por defecto al registrarse por primera vez
    }

    /**
     * Establece si el plugin está habilitado.
     * @param enabled Habilitado
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void updateTargetMarkerFiles(String targetMarkerFiles) {
        this.targetMarkerFiles = targetMarkerFiles;
    }
}
