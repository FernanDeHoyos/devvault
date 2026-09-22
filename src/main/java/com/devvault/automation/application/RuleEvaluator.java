package com.devvault.automation.application;

import com.devvault.automation.application.action.ActionHandler;
import com.devvault.automation.domain.Action;
import com.devvault.automation.domain.AutomationRule;
import com.devvault.automation.domain.Condition;
import com.devvault.automation.domain.Trigger;
import com.devvault.automation.infrastructure.*;
import com.devvault.runtime.domain.event.ProjectFailedEvent;
import com.devvault.runtime.domain.event.ProjectStartedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Implementa CU-12: escucha eventos de dominio de otros módulos (vía el
 * EventBus de Spring, nunca importando su domain directamente — misma
 * regla de arquitectura de siempre), encuentra qué AutomationRule tienen
 * un Trigger que coincide, evalúa sus Condition, y ejecuta sus Action.
 */
@Component
public class RuleEvaluator {

    private static final Logger log = LoggerFactory.getLogger(RuleEvaluator.class);

    private final AutomationRuleRepository ruleRepository;
    private final TriggerRepository triggerRepository;
    private final ConditionRepository conditionRepository;
    private final ActionRepository actionRepository;
    private final Map<String, ActionHandler> actionHandlers; // actionType -> handler
    private final ExpressionParser parser = new SpelExpressionParser();

    public RuleEvaluator(AutomationRuleRepository ruleRepository,
                          TriggerRepository triggerRepository,
                          ConditionRepository conditionRepository,
                          ActionRepository actionRepository,
                          List<ActionHandler> handlers) {
        this.ruleRepository = ruleRepository;
        this.triggerRepository = triggerRepository;
        this.conditionRepository = conditionRepository;
        this.actionRepository = actionRepository;
        this.actionHandlers = handlers.stream()
                .collect(java.util.stream.Collectors.toMap(ActionHandler::actionType, h -> h));
    }

    @EventListener
    public void onProjectFailed(ProjectFailedEvent event) {
        evaluate("ProjectFailedEvent", event.projectId(), Map.of(
                "projectId", event.projectId().toString(),
                "reason", event.reason()
        ));
    }

    @EventListener
    public void onProjectStarted(ProjectStartedEvent event) {
        evaluate("ProjectStartedEvent", event.projectId(), Map.of(
                "projectId", event.projectId().toString()
        ));
    }

    private void evaluate(String eventType, UUID eventProjectId, Map<String, Object> eventContext) {
        List<Trigger> matchingTriggers = triggerRepository.findByEventType(eventType);

        for (Trigger trigger : matchingTriggers) {
            AutomationRule rule = ruleRepository.findById(trigger.getRuleId()).orElse(null);
            if (rule == null || !rule.appliesTo(eventProjectId)) {
                continue;
            }

            if (!conditionsPass(rule.getId(), eventContext)) {
                log.debug(">>> Regla '{}' no cumplió sus condiciones, se omite", rule.getName());
                continue;
            }

            executeActions(rule, eventContext);
        }
    }

    private boolean conditionsPass(UUID ruleId, Map<String, Object> eventContext) {
        List<Condition> conditions = conditionRepository.findByRuleId(ruleId);
        if (conditions.isEmpty()) {
            return true; // sin condiciones = siempre aplica
        }

        StandardEvaluationContext evalContext = new StandardEvaluationContext();
        eventContext.forEach(evalContext::setVariable);

        return conditions.stream().allMatch(condition -> {
            try {
                Boolean result = parser.parseExpression(condition.getExpression())
                        .getValue(evalContext, Boolean.class);
                return Boolean.TRUE.equals(result);
            } catch (Exception e) {
                log.warn(">>> Condición inválida '{}': {}", condition.getExpression(), e.getMessage());
                return false; // condición rota = no aplica, no rompe el flujo
            }
        });
    }

    private void executeActions(AutomationRule rule, Map<String, Object> eventContext) {
        List<Action> actions = actionRepository.findByRuleId(rule.getId());

        for (Action action : actions) {
            ActionHandler handler = actionHandlers.get(action.getActionType());
            if (handler == null) {
                log.warn(">>> No hay ActionHandler registrado para el tipo '{}'", action.getActionType());
                continue;
            }
            try {
                handler.execute(action, eventContext);
            } catch (Exception e) {
                // Regla de negocio: una acción fallida no debe interrumpir las demás
                log.error(">>> Falló la acción '{}' de la regla '{}'", action.getActionType(), rule.getName(), e);
            }
        }
    }
}