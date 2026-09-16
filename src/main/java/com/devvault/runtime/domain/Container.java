package com.devvault.runtime.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "containers")
@Getter
@NoArgsConstructor
public class Container {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "service_id", nullable = false)
    private UUID serviceId;

    @Column(name = "docker_container_id", length = 64)
    private String dockerContainerId;

    @Column(nullable = false, length = 20)
    private String state;

    public Container(UUID serviceId, String dockerContainerId, String state) {
        this.serviceId = serviceId;
        this.dockerContainerId = dockerContainerId;
        this.state = state;
    }

    public void updateState(String state) {
        this.state = state;
    }
}