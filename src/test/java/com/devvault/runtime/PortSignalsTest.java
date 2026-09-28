package com.devvault.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.devvault.runtime.application.PortSignals;

/**
 * Cubre la extracción del puerto a partir de la salida del proceso, que es la
 * parte que fallaba al arrancar proyectos Spring Boot: el puerto nunca se
 * detectaba y el arranque terminaba siempre marcado como FAILED.
 */
class PortSignalsTest {

    @Test
    void shouldDetectTomcatStartedOnPort() {
        PortSignals signals = new PortSignals();
        signals.observe("2026-09-25 10:30:00.123  INFO 12345 --- [main] o.s.b.w.e.t.TomcatWebServer  : "
                + "Tomcat started on port 8080 (http) with context path ''");

        assertEquals(List.of(8080), signals.explicitPorts());
    }

    @Test
    void shouldDetectTomcatInitializedWithPorts() {
        PortSignals signals = new PortSignals();
        signals.observe("o.s.b.w.e.t.TomcatWebServer  : Tomcat initialized with port(s): 9090 (http)");

        assertEquals(List.of(9090), signals.explicitPorts());
    }

    @Test
    void shouldDetectOtherServletContainers() {
        PortSignals signals = new PortSignals();
        signals.observe("Netty started on port 8081");
        signals.observe("Undertow started on port 8082");
        signals.observe("Jetty started on port 8083");

        assertEquals(List.of(8083, 8082, 8081), signals.explicitPorts());
    }

    @Test
    void shouldDetectDynamicPortAboveFiveDigits() {
        PortSignals signals = new PortSignals();
        signals.observe("Tomcat started on port 54321 (http)");

        assertEquals(List.of(54321), signals.explicitPorts());
    }

    /**
     * El grupo de dígitos lleva {@code (?!\d)} justamente para esto: sin él, el
     * backtracking sobre el corchete final parte {@code 8000} y devuelve
     * {@code 00}, que parsea como puerto 0 y se descarta por inválido.
     */
    @Test
    void shouldNotTruncatePortFollowedByDelimiter() {
        PortSignals signals = new PortSignals();
        signals.observe("INFO  Server running on [http://127.0.0.1:8000].");

        assertEquals(List.of(8000), signals.explicitPorts());
    }

    @Test
    void shouldPreferExplicitOverUrlHint() {
        PortSignals signals = new PortSignals();
        signals.observe("Conectando a http://localhost:5432/devvault");
        signals.observe("Tomcat started on port 8080 (http)");

        assertEquals(List.of(8080), signals.explicitPorts());
        assertEquals(List.of(5432), signals.urlHintPorts());
    }

    @Test
    void shouldTrackUrlHintWhenServerSaysNothing() {
        PortSignals signals = new PortSignals();
        signals.observe("  VITE v5.4.0  ready in 312 ms");
        signals.observe("  ➜  Local:   http://localhost:5173/");

        assertTrue(signals.explicitPorts().isEmpty());
        assertEquals(List.of(5173), signals.urlHintPorts());
    }

    @Test
    void shouldReturnMostRecentExplicitPortFirst() {
        PortSignals signals = new PortSignals();
        signals.observe("Tomcat started on port 8080 (http)");
        signals.observe("Tomcat started on port 8081 (http)");

        assertEquals(List.of(8081, 8080), signals.explicitPorts());
    }

    @Test
    void shouldIgnoreNullAndBlankLines() {
        PortSignals signals = new PortSignals();
        signals.observe(null);
        signals.observe("   ");
        signals.observe("");

        assertTrue(signals.explicitPorts().isEmpty());
        assertTrue(signals.urlHintPorts().isEmpty());
    }

    @Test
    void shouldIgnoreUnrelatedPortZero() {
        PortSignals signals = new PortSignals();
        signals.observe("Tomcat started on port 0 (http)");

        assertTrue(signals.explicitPorts().isEmpty());
    }
}
