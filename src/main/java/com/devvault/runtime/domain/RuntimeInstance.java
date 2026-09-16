package com.devvault.runtime.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;


@Entity 
@Table(name = "runtime_instances")
@Getter 
@NoArgsConstructor 
public class RuntimeInstance {
    
    @Id 
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;
    
    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "stopped_at", nullable = false)
    private Instant stoppedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "overall_status", nullable = false, length = 20)
    private RuntimeStatus overallStatus;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

     public RuntimeInstance(UUID projectId) {
        this.projectId = projectId;
        this.startedAt = Instant.now();
        this.overallStatus = RuntimeStatus.STARTING;
    }
 
    /**
    * Marca el estado del entorno como en ejecución
    * y limpia cualquier motivo de fallo anterior.
    */
    public void markRunning() {
        this.overallStatus = RuntimeStatus.RUNNING;
        this.failureReason = null;
    }
 
    /**
    * Marca el estado del entorno como Fallido
    * y limpia cualquier motivo de fallo anterior.
    */
    public void markFailed(String reason) {
        this.overallStatus = RuntimeStatus.FAILED;
        this.failureReason = reason;
    }
 
    /**
    * Marca el estado del entorno como Detenido
    * y limpia cualquier motivo de fallo anterior.
    */
    public void markStopped() {
        this.overallStatus = RuntimeStatus.STOPPED;
        this.stoppedAt = Instant.now();
    }
 
    public boolean isActive() {
        return overallStatus == RuntimeStatus.RUNNING || overallStatus == RuntimeStatus.STARTING;
    }
}
