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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ContainerKind kind;

    /** Solo aplica cuando kind = LOCAL_PROCESS. */
    private Integer pid;

    /** Solo aplica cuando kind = LOCAL_PROCESS — el comando real ejecutado, ej. "npm run dev". */
    @Column(columnDefinition = "TEXT")
    private String command;

    /** Constructor para contenedores Docker (comportamiento original, sin cambios). */
    public Container(UUID serviceId, String dockerContainerId, String state) {
        this.serviceId = serviceId;
        this.dockerContainerId = dockerContainerId;
        this.state = state;
        this.kind = ContainerKind.DOCKER;
    }

    /** Constructor para procesos locales (npm run dev, etc). */
    public static Container localProcess(UUID serviceId, int pid, String command, String state) {
        Container container = new Container();
        container.serviceId = serviceId;
        container.pid = pid;
        container.command = command;
        container.state = state;
        container.kind = ContainerKind.LOCAL_PROCESS;
        return container;
    }

    public void updateState(String state) {
        this.state = state;
    }

    public void updatePid(Integer pid) {
        this.pid = pid;
    }

    public void updateCommand(String command) {
        this.command = command;
    }

    public boolean isLocalProcess() {
        return kind == ContainerKind.LOCAL_PROCESS;
    }
    public void updateDockerContainerId(String dockerContainerId) {
    this.dockerContainerId = dockerContainerId;
}
}