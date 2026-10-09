package com.devvault.shared.config;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rechaza peticiones que llegan con una cabecera {@code Origin} de otro origen.
 *
 * <p>DevVault se ejecuta en local y enlaza a {@code 127.0.0.1}, así que la
 * amenaza realista no es un atacante remoto: es cualquier pagina web que
 * estes mirando. El navegador si bloquea la <em>lectura</em> de la respuesta
 * de otro origen por CORS, pero eso no impide que la <em>peticion</em> se
 * ejecute. Una peticion "simple", la que no lleva cabeceras raras ni cuerpo
 * tipado, se manda igual y solo se descarta al intentar leer la respuesta.
 *
 * <p>Eso significa que sin este filtro, visitar una pagina cualquiera podria
 * acabar en {@code POST /api/v1/projects/{id}/stop} —que no lleva cuerpo ni
 * cabeceras especiales, luego es simple— y parar los proyectos del usuario, o
 * en {@code POST /api/v1/projects/{id}/open} y lanzar su editor.
 *
 * <p>El filtro no depende de CORS ni lo duplica: CORS regula que puede
 * <em>leer</em> una respuesta de la API, y esto regula quien puede <em>hacer</em>
 * la peticion. Las peticiones sin {@code Origin} se dejan pasar porque no las
 * produce un navegador: son las de curl, Postman o cualquier cliente de linea de
 * comandos.
 */
@Component
@Order(1)
public class SameOriginFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SameOriginFilter.class);

    /** Origenes propios conocidos: la UI de Vite y la preview empaquetada. */
    private static final Set<String> ALLOWED_ORIGINS = Set.of(
            "http://localhost:5050",
            "http://127.0.0.1:5050",
            "http://localhost:8080",
            "http://127.0.0.1:8080");

    /**
     * El handshake de WebSocket no lleva {@code Origin} como el resto, pero los
     * navegadores si lo mandan, asi que tambien queda cubierto.
     */
    private static final List<String> PROTECTED_PREFIXES = List.of("/api/", "/ws");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return PROTECTED_PREFIXES.stream().noneMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String origin = request.getHeader("Origin");
        if (origin == null || origin.isBlank() || isAllowed(request, origin)) {
            filterChain.doFilter(request, response);
            return;
        }

        log.warn("Peticion bloqueada desde el origen '{}' hacia {} {}",
                origin, request.getMethod(), request.getRequestURI());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"status\":403,\"error\":\"Forbidden\","
                + "\"message\":\"Origen no permitido para una peticion local.\"}");
    }

    /**
     * Se acepta el origen cuando esta en la lista conocida <em>o</em> cuando
     * coincide con el de la propia peticion.
     *
     * <p>Lo segundo evita tener la lista atada a puertos fijos: si DevVault
     * arranca en otro puerto, una peticion de la propia UI llega con
     * {@code Origin} apuntando a ese puerto, y sin esta comprobacion se
     * rechazaria. Es ademas lo que define "mismo origen" de forma explicita, en
     * vez de dejarlo en manos de la deteccion automatica de Spring.
     *
     * <p>Nótese que {@code Origin: null} no coincide nunca con el origen de la
     * peticion, asi que queda bloqueado. Lo envian los iframes con sandbox y
     * las paginas {@code data:}, que no deberian poder accionar DevVault.
     */
    private boolean isAllowed(HttpServletRequest request, String origin) {
        if (ALLOWED_ORIGINS.contains(origin)) {
            return true;
        }
        String host = request.getHeader("Host");
        if (host == null || host.isBlank()) {
            return false;
        }
        return origin.equals(request.getScheme() + "://" + host);
    }
}