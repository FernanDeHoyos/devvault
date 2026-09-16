package com.devvault.runtime.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "services")
@Getter
@NoArgsConstructor
public class Service {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false, length = 30)
    private String type;

    private Integer port;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ServiceStatus status;

    public Service(UUID projectId, String name, String type, Integer port) {
        this.projectId = projectId;
        this.name = name;
        this.type = type;
        this.port = port;
        this.status = ServiceStatus.STARTING;
    }

    public void updateStatus(ServiceStatus status) {
        this.status = status;
    }
}
