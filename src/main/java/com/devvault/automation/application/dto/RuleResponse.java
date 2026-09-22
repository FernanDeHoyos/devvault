package com.devvault.automation.application.dto;

import com.devvault.automation.domain.Action;
import com.devvault.automation.domain.AutomationRule;
import com.devvault.automation.domain.Condition;
import com.devvault.automation.domain.Trigger;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record RuleResponse(
        UUID id,
        String name,
        UUID projectId,
        boolean enabled,
        List<String> triggers,
        List<String> conditions,
        List<ActionInfo> actions
) {
    public record ActionInfo(String actionType, Map<String, Object> params) {
    }

    public static RuleResponse from(AutomationRule rule, List<Trigger> triggers,
                                     List<Condition> conditions, List<Action> actions) {
        return new RuleResponse(
                rule.getId(),
                rule.getName(),
                rule.getProjectId(),
                rule.isEnabled(),
                triggers.stream().map(Trigger::getEventType).toList(),
                conditions.stream().map(Condition::getExpression).toList(),
                actions.stream().map(a -> new ActionInfo(a.getActionType(), a.getParams())).toList()
        );
    }
}