package com.devvault.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Permite que el frontend servido por Vite (puerto 5050 en desarrollo) llame a
 * la API REST del 8080 — sin esto el navegador bloquea la lectura de la
 * respuesta antes de que llegue al controlador.
 *
 * <p>Esto solo aplica en desarrollo. En la preview empaquetada la UI se sirve
 * desde el propio backend, asi que todo es del mismo origen y no hay CORS que
 * negociar.
 *
 * <p>Lo que CORS no cubre, y por eso existe {@link SameOriginFilter}: CORS
 * regula que puede <em>leer</em> una respuesta, no quien puede <em>enviar</em> la
 * peticion.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5050", "http://127.0.0.1:5050")
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Authorization", "Content-Type")
                .exposedHeaders("Authorization");
    }
}
