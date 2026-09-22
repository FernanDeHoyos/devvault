package com.devvault.automation.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record CreateRuleRequest(
        @NotBlank String name,
        UUID projectId, // opcional: null = regla global
        @Valid @NotEmpty List<TriggerRequest> triggers,
        @Valid List<ConditionRequest> conditions,
        @Valid @NotEmpty List<ActionRequest> actions
) {
    public record TriggerRequest(@NotBlank String eventType) {
    }

    public record ConditionRequest(@NotBlank String expression) {
    }

    public record ActionRequest(@NotBlank String actionType, Map<String, Object> params) {
    }
}