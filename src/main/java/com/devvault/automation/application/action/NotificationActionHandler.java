package com.devvault.automation.application.action;

import com.devvault.automation.domain.Action;
import com.devvault.monitoring.domain.Alert;
import com.devvault.monitoring.infrastructure.AlertRepository;
import org.springframework.stereotype.Component;
import java.util.Map;
import java.util.UUID;

@Component
public class NotificationActionHandler implements ActionHandler {
    private final AlertRepository alerts;
    public NotificationActionHandler(AlertRepository alerts) { this.alerts = alerts; }
    public String actionType() { return "NOTIFY"; }
    public void execute(Action action, Map<String, Object> context) {
        String message = String.valueOf(action.getParams().getOrDefault("message", "Regla activada"));
        alerts.save(new Alert(UUID.fromString(String.valueOf(context.get("projectId"))),
                "AUTOMATION", MessageTemplate.render(message, context)));
    }
}
