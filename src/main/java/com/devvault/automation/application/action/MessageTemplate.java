package com.devvault.automation.application.action;

import java.util.Map;
import java.util.regex.Pattern;

/** Templates substitute event fields, never execute expressions or methods. */
public final class MessageTemplate {
    private static final Pattern FIELD = Pattern.compile("#\\{([A-Za-z][A-Za-z0-9_]*)}");
    private MessageTemplate() {}
    public static String render(String template, Map<String, Object> context) {
        var matcher = FIELD.matcher(template);
        return matcher.replaceAll(match -> java.util.regex.Matcher.quoteReplacement(
                String.valueOf(context.getOrDefault(match.group(1), "[no disponible]"))));
    }
}
