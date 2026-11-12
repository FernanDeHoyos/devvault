package com.devvault.automation.application;

import com.devvault.automation.application.action.ActionHandler;
import com.devvault.automation.domain.*;
import com.devvault.automation.infrastructure.*;
import com.devvault.runtime.domain.event.ProjectFailedEvent;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;

class RuleEvaluatorTest {
    @Test void supportsFieldComparisonButRejectsTypeAccessAndMethodCalls() {
        var rules = mock(AutomationRuleRepository.class);
        var triggers = mock(TriggerRepository.class);
        var conditions = mock(ConditionRepository.class);
        var actions = mock(ActionRepository.class);
        var handler = mock(ActionHandler.class);
        when(handler.actionType()).thenReturn("LOG");
        UUID ruleId = UUID.randomUUID(), projectId = UUID.randomUUID();
        var rule = mock(AutomationRule.class);
        when(rule.getId()).thenReturn(ruleId);
        when(rule.appliesTo(projectId)).thenReturn(true);
        when(rules.findById(ruleId)).thenReturn(Optional.of(rule));
        when(triggers.findByEventType("ProjectFailedEvent")).thenReturn(List.of(new Trigger(ruleId, "ProjectFailedEvent")));
        var action = new Action(ruleId, "LOG", Map.of("message", "falló"));
        when(actions.findByRuleId(ruleId)).thenReturn(List.of(action));
        var evaluator = new RuleEvaluator(rules, triggers, conditions, actions, List.of(handler));
        when(conditions.findByRuleId(ruleId)).thenReturn(List.of(new Condition(ruleId, "#reason == 'puerto'")));
        evaluator.onProjectFailed(new ProjectFailedEvent(projectId, "puerto"));
        verify(handler).execute(eq(action), anyMap());
        clearInvocations(handler);
        for (String expression : List.of("T(java.lang.System).setProperty('devvault.test', 'bad') == null", "#reason.getClass() != null", "new java.lang.String('x') != null")) {
            when(conditions.findByRuleId(ruleId)).thenReturn(List.of(new Condition(ruleId, expression)));
            evaluator.onProjectFailed(new ProjectFailedEvent(projectId, "puerto"));
        }
        verifyNoInteractions(handler);
    }
}
