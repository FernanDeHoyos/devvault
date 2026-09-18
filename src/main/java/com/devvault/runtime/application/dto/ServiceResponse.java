package com.devvault.runtime.application.dto;

import com.devvault.runtime.domain.Service;
import com.devvault.runtime.domain.Container;

import java.util.UUID;

/**
 * DTO para la respuesta de un servicio.
 */
public record ServiceResponse(
        UUID id,
        String name,
        String type,
        Integer port,
        String status,
        String kind,              // "DOCKER" o "LOCAL_PROCESS"
        Integer pid,              // solo LOCAL_PROCESS
        String command,           // solo LOCAL_PROCESS
        String dockerContainerId  // solo DOCKER
    ) {
    public static ServiceResponse from(Service service, Container container) {
        return new ServiceResponse(
                service.getId(),
                service.getName(),
                service.getType(),
                service.getPort(),
                service.getStatus().name(),
                container != null ? container.getKind().name() : null,
                container != null ? container.getPid() : null,
                container != null ? container.getCommand() : null,
                container != null ? container.getDockerContainerId() : null
            );
    }
}