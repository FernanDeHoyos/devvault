package com.devvault.automation.application.action;

import com.devvault.automation.domain.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Acción más simple posible: deja un registro visible en consola. Sirve
 * para validar que todo el motor de reglas funciona de punta a punta antes
 * de construir acciones más complejas (notificaciones reales, etc.).
 *
 * params soporta sustitución literal de campos entre #{...}, ej:
 * { "message": "El proyecto #{projectId} falló: #{reason}" }
 */
@Component
public class LogActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger("com.devvault.automation.RuleAction");

    @Override
    public String actionType() {
        return "LOG";
    }

    @Override
    public void execute(Action action, Map<String, Object> eventContext) {
        String template = (String) action.getParams().getOrDefault("message", "Regla activada (sin mensaje configurado)");
        String rendered = renderTemplate(template, eventContext);
        log.info(">>> [AUTOMATION] {}", rendered);
    }

    private String renderTemplate(String template, Map<String, Object> context) {
        return MessageTemplate.render(template, context);
    }
}
