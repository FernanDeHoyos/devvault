package com.devvault.automation.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;


@Entity
@Table(name = "automation_rules")
@Getter 
@NoArgsConstructor
public class AutomationRule {

    @Id 
    @GeneratedValue 
    private UUID id;
 
    @Column(name = "project_id")
    private UUID projectId; // nullable: regla global si es null
 
    @Column(nullable = false, length = 120)
    private String name;
 
    @Column(name = "is_enabled", nullable = false)
    private boolean enabled;
 
    public AutomationRule(UUID projectId, String name, boolean enabled) {
        this.projectId = projectId;
        this.name = name;
        this.enabled = enabled;
    }
 
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
 
    /** Regla de negocio: solo aplica si está habilitada Y (es global O coincide con el proyecto del evento). */
    public boolean appliesTo(UUID eventProjectId) {
        return enabled && (projectId == null || projectId.equals(eventProjectId));
    }
}
