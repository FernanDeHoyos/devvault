package com.devvault.automation.application.action;

import com.devvault.automation.domain.Action;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Acción más simple posible: deja un registro visible en consola. Sirve
 * para validar que todo el motor de reglas funciona de punta a punta antes
 * de construir acciones más complejas (notificaciones reales, etc.).
 *
 * params soporta una plantilla simple con SpEL entre #{...}, ej:
 * { "message": "El proyecto #{projectId} falló: #{reason}" }
 */
@Component
public class LogActionHandler implements ActionHandler {

    private static final Logger log = LoggerFactory.getLogger("com.devvault.automation.RuleAction");
    private final ExpressionParser parser = new SpelExpressionParser();

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
        StandardEvaluationContext evalContext = new StandardEvaluationContext();
        context.forEach(evalContext::setVariable);

        StringBuilder result = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int start = template.indexOf("#{", i);
            if (start == -1) {
                result.append(template.substring(i));
                break;
            }
            result.append(template, i, start);
            int end = template.indexOf('}', start);
            if (end == -1) {
                result.append(template.substring(start));
                break;
            }
            String spelExpr = "#" + template.substring(start + 2, end);
            try {
                Expression expression = parser.parseExpression(spelExpr);
                Object value = expression.getValue(evalContext);
                result.append(value != null ? value.toString() : "null");
            } catch (Exception e) {
                result.append("[error: ").append(e.getMessage()).append("]");
            }
            i = end + 1;
        }
        return result.toString();
    }
}