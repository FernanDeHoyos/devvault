package com.devvault.discovery.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;


@Entity
@Table(name = "projects")
@Getter
@NoArgsConstructor
public class Project {
    @Id
    @GeneratedValue(strategy = jakarta.persistence.GenerationType.UUID)
    private UUID id;

    @Column(name = "workspace_id", nullable = false)  
    private UUID workspaceId;

    @Column(nullable = false, length = 125)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String path;

    private String language;
    private String framework;
    private String version; 

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProjectStatus status;

    public Project(UUID workspaceId, String name, String path, String language, String framework, String version, ProjectStatus status) {
        this.workspaceId = workspaceId;
        this.name = name;
        this.path = path;
        this.language = language;
        this.framework = framework;
        this.version = version;
        this.status = status;
    }

    public void updateDetection(String language, String framework, String version) {
        this.language = language;
        this.framework = framework;
        this.version = version;
    }

    public void markNotFound() {
        this.status = ProjectStatus.NOT_FOUND;
    }
}
