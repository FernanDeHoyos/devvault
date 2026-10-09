package com.devvault.shared.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Cubre el guardia que sustituye a la autenticación.
 *
 * <p>Existe por una razón concreta: sin JWT, cualquier página web que visite el
 * usuario puede lanzar peticiones a {@code 127.0.0.1}. CORS impide leer la
 * respuesta, pero una peticion "simple" se ejecuta igual. Sin este filtro,
 * una pagina ajena podria parar los proyectos del usuario o lanzar su editor.
 */
class SameOriginFilterTest {

    private final SameOriginFilter filter = new SameOriginFilter();

    private MockHttpServletResponse invoke(String path, String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        if (origin != null) {
            request.addHeader(HttpHeaders.ORIGIN, origin);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] continued = { false };
        filter.doFilter(request, response, (req, res) -> continued[0] = true);
        assertEquals(continued[0], response.getStatus() < 400,
                "la cadena solo debe continuar si la peticion no fue bloqueada");
        return response;
    }

    @Test
    void shouldBlockForeignOrigin() throws Exception {
        MockHttpServletResponse response = invoke("/api/v1/projects",
                "https://sitio-malicioso.example");
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("Origen no permitido"));
    }

    @Test
    void shouldAllowOwnOrigins() throws Exception {
        for (String origin : new String[] {
                "http://localhost:5050", "http://127.0.0.1:5050",
                "http://localhost:8080", "http://127.0.0.1:8080" }) {
            assertEquals(200, invoke("/api/v1/projects", origin).getStatus(),
                    "debería permitir el origen propio " + origin);
        }
    }

    /**
     * Las peticiones sin Origin no las produce un navegador: son curl, Postman o
     * cualquier cliente de linea de comandos, y deben poder hablar con la API.
     */
    @Test
    void shouldAllowRequestsWithoutOrigin() throws Exception {
        assertEquals(200, invoke("/api/v1/projects", null).getStatus());
        assertEquals(200, invoke("/api/v1/projects", "  ").getStatus());
    }

    /**
     * `Origin: null` lo mandan de verdad los navegadores desde iframes con
     * sandbox y paginas `data:`. Aceptarlo abriria un agujero real, asi que se
     * bloquea igual que cualquier otro origen desconocido.
     */
    @Test
    void shouldBlockLiteralNullOrigin() throws Exception {
        assertEquals(403, invoke("/api/v1/projects", "null").getStatus());
    }

    @Test
    void shouldAllowRequestOwnOriginOnAnyPort() throws Exception {
        // La lista de origenes conocidos no puede quedar atada a un puerto fijo:
        // si DevVault arranca en otro, la UI llega con Origin de ese puerto y
        // tiene que pasar igual.
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/projects/abc/stop");
        request.setScheme("http");
        request.addHeader(HttpHeaders.HOST, "127.0.0.1:9100");
        request.addHeader(HttpHeaders.ORIGIN, "http://127.0.0.1:9100");

        MockHttpServletResponse response = new MockHttpServletResponse();
        boolean[] continued = { false };
        filter.doFilter(request, response, (req, res) -> continued[0] = true);

        assertTrue(continued[0], "una peticion del propio origen debe pasar");
        assertEquals(200, response.getStatus());
    }

    @Test
    void shouldCoverWebSocketHandshake() throws Exception {
        // El handshake de WebSocket no pasa por los mappings de Spring MVC, asi
        // que el filtro tiene que cubrir la ruta por su cuenta.
        assertEquals(403, invoke("/api/v1/projects/abc/logs", "https://sitio-malicioso.example").getStatus());
    }

    @Test
    void shouldNotFilterUnrelatedPaths() throws Exception {
        // La UI estatica y los recursos del navegador no llevan Origin y deben
        // pasar sin pasar por el filtro.
        Optional<MockHttpServletRequest> request = Optional.of(new MockHttpServletRequest("GET", "/index.html"));
        request.ifPresent(req ->
                org.junit.jupiter.api.Assertions.assertTrue(filter.shouldNotFilter(req)));
        assertTrue(filter.shouldNotFilter(new MockHttpServletRequest("GET", "/assets/index.js")));
        assertEquals(false, filter.shouldNotFilter(new MockHttpServletRequest("GET", "/api/v1/health")));
    }
}