package com.devvault.automation.application.action;

import com.devvault.automation.domain.Action;

import java.util.Map;

/**
 * Contrato de una acción ejecutable (patrón Strategy, igual que
 * TechnologyPlugin). Agregar un tipo de acción nuevo (ej. NOTIFY vía
 * Telegram/Discord en el futuro) es solo agregar una implementación nueva,
 * sin tocar RuleEvaluator.
 */
public interface ActionHandler {

    String actionType();

    void execute(Action action, Map<String, Object> eventContext);
}