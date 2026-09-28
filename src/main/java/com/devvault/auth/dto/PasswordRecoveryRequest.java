package com.devvault.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PasswordRecoveryRequest(
        @NotBlank @Size(min = 3, max = 64) String username,
        @NotBlank @Size(min = 32, max = 128) String recoveryKey,
        @NotBlank @Size(min = 12, max = 128) String newPassword) {}
