package com.devvault.monitoring.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "alerts")
@Getter
@NoArgsConstructor
public class Alert {
    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 80)
    private String type;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AlertStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public Alert(UUID projectId, String type, String message) {
        this.projectId = projectId;
        this.type = type;
        this.message = message;
        this.status = AlertStatus.ACTIVE;
        this.createdAt = Instant.now();
    }

    public void resolve() {
        if (status == AlertStatus.ACTIVE) {
            status = AlertStatus.RESOLVED;
            resolvedAt = Instant.now();
        }
    }
}
