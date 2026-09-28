package com.devvault.shared.api;

import static org.junit.jupiter.api.Assertions.fail;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.MatrixVariable;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Spring deduce el nombre de un parámetro por reflexión cuando la anotación no
 * lo declara, y para eso necesita que el compilador haya usado {@code
 * -parameters}. El proyecto lo hace en Gradle, pero el mismo código compilado
 * desde Eclipse o desde otra IDE puede salir sin ese flag, y entonces el
 * endpoint revienta en tiempo de ejecución con:
 *
 * <pre>
 * Name for argument of type [...] not specified, and parameter name information
 * not available via reflection. Ensure that the compiler uses the '-parameters' flag.
 * </pre>
 *
 * Ese fallo solo se reproduce en el entorno que compila distinto, que es
 * justo donde uno no lo detecta. Por eso los nombres van explícitos en las
 * anotaciones y este test lo vigila.
 *
 * <p>Ojo al leer {@code @PathVariable("id")}: el argumento posicional va a
 * {@code value()}, no a {@code name()}. Spring resuelve el alias entre ambos al
 * procesar la petición, pero la reflexión de la JDK devuelve la anotación en
 * crudo y solo ve el elemento que se escribió de verdad. Por eso aquí se
 * comprueban los dos.
 */
class ControllerParameterNamesTest {

    private static final List<Class<? extends Annotation>> NAMED_ANNOTATIONS = List.of(
            RequestParam.class, PathVariable.class, RequestHeader.class,
            MatrixVariable.class, RequestAttribute.class);

    @Test
    void shouldDeclareExplicitNamesOnAllControllerParameters() throws Exception {
        List<String> offenders = new ArrayList<>();

        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        for (var candidate : scanner.findCandidateComponents("com.devvault")) {
            Class<?> controller = Class.forName(candidate.getBeanClassName());
            for (Method method : controller.getDeclaredMethods()) {
                for (int i = 0; i < method.getParameterCount(); i++) {
                    Parameter parameter = method.getParameters()[i];
                    String label = controller.getSimpleName() + "#" + method.getName()
                            + " (parametro " + i + ", tipo " + parameter.getType().getSimpleName() + ")";
                    for (Class<? extends Annotation> type : NAMED_ANNOTATIONS) {
                        check(parameter, type, label, offenders);
                    }
                }
            }
        }

        if (!offenders.isEmpty()) {
            fail("Estas anotaciones no declaran el nombre del parametro y dependen del flag "
                    + "-parameters del compilador:" + System.lineSeparator()
                    + "  " + String.join(System.lineSeparator() + "  ", offenders));
        }
    }

    private void check(Parameter parameter, Class<? extends Annotation> type, String label,
            List<String> offenders) {
        Annotation annotation = parameter.getAnnotation(type);
        if (annotation == null) {
            return;
        }
        if (isBlank(attribute(annotation, "name")) && isBlank(attribute(annotation, "value"))) {
            offenders.add("@" + type.getSimpleName() + " sin name/value explicito en " + label);
        }
    }

    private String attribute(Annotation annotation, String name) {
        try {
            Object value = annotation.annotationType().getMethod(name).invoke(annotation);
            return value == null ? null : value.toString();
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
