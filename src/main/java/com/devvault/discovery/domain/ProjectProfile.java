package com.devvault.discovery.domain;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

// string id PK
// string projectId FK
// datetime detectedAt
// json rawMarkers

@Entity
@NoArgsConstructor
@Table(name = "project_profiles")
@Getter
public class ProjectProfile {

    @Id
    @GeneratedValue(strategy = jakarta.persistence.GenerationType.UUID)
    private UUID id;

    // clave foranea a la tabla projects, para relacionar el archivo con el proyecto al que pertenece
    @Column(name = "project_id", nullable = false, unique = true)
    private UUID projectId;

    // fecha y hora en que se detecto el proyecto
    @Column(name = "detected_at", nullable = false)
    private Instant detectedAt;

    // jsonb para almacenar los marcadores detectados en el proyecto, 
    // como lenguaje, framework y version
    // @JdbcTypeCode es una anotación de Hibernate que 
    // indica el tipo de datos SQL que se debe usar para mapear 
    // la propiedad rawMarkers.
    @JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)
    @Column(name = "raw_markers", columnDefinition = "jsonb")
    private Map<String, Object> rawMarkers;


    public ProjectProfile(UUID projectId, Map<String, Object> rawMarkers) {
        this.projectId = projectId;
        this.detectedAt = Instant.now();
        this.rawMarkers = rawMarkers;
    }

    /**
     * Actualiza la fecha de detección y los marcadores detectados del proyecto.
     * @param rawMarkers un mapa con los marcadores detectados en el proyecto
     */
    public void refreshDetection(Map<String, Object> rawMarkers) {
        this.detectedAt = Instant.now();
        this.rawMarkers = rawMarkers;
    }
    
}
