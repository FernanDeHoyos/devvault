package com.devvault.monitoring.application.dto;

import jakarta.validation.constraints.NotNull;

public record ResolveAlertRequest(@NotNull Boolean resolved) { }
