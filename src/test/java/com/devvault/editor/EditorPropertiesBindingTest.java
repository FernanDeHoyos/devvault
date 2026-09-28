package com.devvault.editor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Comprueba que la clave declarada en {@code application.yml} liga de verdad
 * con el componente del record {@link EditorProperties}.
 *
 * <p>Existe porque el fallo es silencioso y por eso mismo es peligroso. Con
 * {@code devvault.editor.default} en vez de {@code default-editor}, Spring no
 * encuentra el componente, no hay error de binding porque el campo es
 * nullable, la aplicación arranca perfectamente y el editor configurado
 * simplemente se ignora. Es el peor tipo de bug: no se manifiesta hasta que
 * alguien abre un proyecto y se abre en el editor que no quiere.
 */
class EditorPropertiesBindingTest {

    @Test
    void shouldBindDefaultEditorFromApplicationYml() throws Exception {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> sources = loader.load("application.yml",
                new ClassPathResource("application.yml"));

        ConfigurableEnvironment environment = new StandardEnvironment();
        sources.forEach(environment.getPropertySources()::addLast);

        EditorProperties properties = Binder.get(environment)
                .bind("devvault.editor", EditorProperties.class)
                .orElseThrow(() -> new AssertionError(
                        "No se pudo ligar devvault.editor a EditorProperties. "
                                + "Revisa que la clave del YAML coincida con el nombre del componente."));

        assertEquals("vscode", properties.normalizedDefault(),
                "la clave del YAML debe ligar con el componente defaultEditor del record");
    }
}
