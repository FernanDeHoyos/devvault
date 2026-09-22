package com.devvault.automation.application;

import com.devvault.automation.application.dto.*;
import com.devvault.automation.domain.Action;
import com.devvault.automation.domain.AutomationRule;
import com.devvault.automation.domain.Condition;
import com.devvault.automation.domain.Trigger;
import com.devvault.automation.infrastructure.ActionRepository;
import com.devvault.automation.infrastructure.AutomationRuleRepository;
import com.devvault.automation.infrastructure.ConditionRepository;
import com.devvault.automation.infrastructure.TriggerRepository;
import com.devvault.shared.api.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class AutomationRuleService {

    private final AutomationRuleRepository ruleRepository;
    private final TriggerRepository triggerRepository;
    private final ConditionRepository conditionRepository;
    private final ActionRepository actionRepository;

    public AutomationRuleService(AutomationRuleRepository ruleRepository,
                                  TriggerRepository triggerRepository,
                                  ConditionRepository conditionRepository,
                                  ActionRepository actionRepository) {
        this.ruleRepository = ruleRepository;
        this.triggerRepository = triggerRepository;
        this.conditionRepository = conditionRepository;
        this.actionRepository = actionRepository;
    }

    @Transactional
    public RuleResponse create(CreateRuleRequest request) {
        // Regla de negocio (RF-19/CU-11): sin al menos un Trigger, la regla nunca se activaría
        if (request.triggers() == null || request.triggers().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Una regla necesita al menos un Trigger");
        }
        if (request.actions() == null || request.actions().isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Una regla necesita al menos una Action");
        }

        AutomationRule rule = ruleRepository.save(new AutomationRule(request.projectId(), request.name(), true));

        request.triggers().forEach(t -> triggerRepository.save(new Trigger(rule.getId(), t.eventType())));
        if (request.conditions() != null) {
            request.conditions().forEach(c -> conditionRepository.save(new Condition(rule.getId(), c.expression())));
        }
        request.actions().forEach(a -> actionRepository.save(new Action(rule.getId(), a.actionType(), a.params())));

        return toResponse(rule);
    }

    public List<RuleResponse> findAll() {
        return ruleRepository.findAll().stream().map(this::toResponse).toList();
    }

    public RuleResponse findById(UUID id) {
        return toResponse(getOrThrow(id));
    }

    @Transactional
    public RuleResponse update(UUID id, UpdateRuleRequest request) {
        AutomationRule rule = getOrThrow(id);
        if (request.isEnabled() != null) {
            rule.setEnabled(request.isEnabled());
        }
        return toResponse(ruleRepository.save(rule));
    }

    @Transactional
    public void delete(UUID id) {
        getOrThrow(id); // valida que exista antes de intentar borrar
        triggerRepository.deleteByRuleId(id);
        conditionRepository.deleteByRuleId(id);
        actionRepository.deleteByRuleId(id);
        ruleRepository.deleteById(id);
    }

    private AutomationRule getOrThrow(UUID id) {
        return ruleRepository.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Regla no encontrada: " + id));
    }

    private RuleResponse toResponse(AutomationRule rule) {
        return RuleResponse.from(
                rule,
                triggerRepository.findByRuleId(rule.getId()),
                conditionRepository.findByRuleId(rule.getId()),
                actionRepository.findByRuleId(rule.getId())
        );
    }
}