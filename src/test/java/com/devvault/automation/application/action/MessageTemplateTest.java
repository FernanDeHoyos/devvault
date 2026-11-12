package com.devvault.automation.application.action;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class MessageTemplateTest {
    @Test void substitutesFieldsLiterallyWithoutEvaluatingCode() {
        assertEquals("Proyecto $test\\ falló", MessageTemplate.render("Proyecto #{projectId} falló", Map.of("projectId", "$test\\")));
        String expression = "#{T(java.lang.Runtime).getRuntime().exec('calc')}";
        assertEquals(expression, MessageTemplate.render(expression, Map.of()));
        assertEquals("[no disponible]", MessageTemplate.render("#{unknown}", Map.of()));
    }
}
