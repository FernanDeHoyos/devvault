package com.devvault.auth.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record WebSocketTicketRequest(@NotNull UUID projectId, @NotBlank String serviceName) {}
