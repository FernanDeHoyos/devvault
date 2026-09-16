package com.devvault.runtime.application.dto;

import com.devvault.runtime.domain.Service;

import java.util.UUID;

/**
 * DTO para la respuesta de un servicio.
 */
public record ServiceResponse(
        UUID id,
        String name,
        String type,
        Integer port,
        String status) {
    public static ServiceResponse from(Service service) {
        return new ServiceResponse(
                service.getId(),
                service.getName(),
                service.getType(),
                service.getPort(),
                service.getStatus().name());
    }
}