package com.devvault.workspace.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@NoArgsConstructor
@Table(name = "workspaces")
@Getter
public class Workspace {

    public Workspace(UUID userId, String name, String path) {
        this.userId = userId;
        this.name = name;
        this.path = path;
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id; //UUID es un identificador único universal, de 128 bits.

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 125)
    private String name;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String path;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist //este metodo se ejecuta antes de que se persista la entidad en la base de datos
    void onCreated(){    // Instant.now() devuelve la fecha y hora actual en UTC
        this.createdAt = Instant.now();
    }

}