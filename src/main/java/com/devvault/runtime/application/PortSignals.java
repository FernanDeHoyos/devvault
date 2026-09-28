package com.devvault.runtime.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puertos que un proceso local declara en su salida, separados por nivel de
 * confianza.
 *
 * <p>El proyecto aplica aquí la misma regla que usa en el resto del runtime:
 * no confiar en un supuesto, verificar contra la realidad. Por eso las señales
 * se clasifican en dos niveles y nunca se mezclan:
 *
 * <ul>
 *   <li><b>Explícitas:</b> el proceso dice literalmente "estoy escuchando en el
 *       puerto N" ({@code Tomcat started on port 8080}). Es la señal más fuerte
 *       posible y una nueva línea explícita desplaza a la anterior.</li>
 *   <li><b>Pistas:</b> aparece una URL a localhost en algún log
 *       ({@code http://localhost:5173/}). En un proyecto en desarrollo casi
 *       siempre es su propio puerto, pero una llamada a otro servicio también
 *       produce ese tipo de línea, así que solo se prueban si nada mejor
 *       respondió.</li>
 * </ul>
 *
 * <p>Esta clase solo recoge señales: no abre sockets ni decide nada. Eso lo
 * hace {@link LocalProcessHealthProbe}, que además puede completar el panorama
 * con la verdad del sistema operativo cuando la salida del proceso no dice nada.
 *
 * <p>Una instancia por ejecución: la escribe el hilo que drena la salida del
 * proceso y la lee el hilo que espera el puerto, por eso las lecturas y las
 * escrituras están sincronizadas.
 */
public class PortSignals {

    /** Cuántos puertos por nivel se recuerdan, para no crecer sin límite. */
    private static final int MAX_TRACKED = 8;

    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    /**
     * Mensajes de arranque del servidor web. El grupo de dígitos lleva
     * {@code (?!\d)} a propósito: sin él, un puerto de cinco cifras puede
     * capturarse partido en dos y devolver un número inventado (por ejemplo
     * {@code 8000} matcheando como {@code 00}) al hacer backtracking sobre un
     * delimitador como {@code ]} o {@code /}.
     */
    private static final List<Pattern> EXPLICIT_PATTERNS = List.of(
            // Spring Boot / Jetty / Undertow / Netty:
            //   "Tomcat started on port 8080 (http) with context path ''"
            //   "Tomcat initialized with port(s): 8080 (http)"
            //   "Jetty started on port 8080", "Netty started on port 8080"
            Pattern.compile(
                    "\\b(?:Tomcat|Jetty|Undertow|Netty|HttpServer)\\b[^\\r\\n]{0,40}?\\bport(?:\\(s\\))?\\s*[:=]?\\s*(\\d{1,5})(?!\\d)",
                    Pattern.CASE_INSENSITIVE),
            // Genérico: "listening on port 8080", "running on port 3000",
            // "listening on http://localhost:8080"
            Pattern.compile(
                    "\\b(?:listening|running|bound|serving|available)\\s+(?:on|at)\\s+\\[?(?:port\\s*[:=]?\\s*|https?://[^\\s\\]]+)?(\\d{2,5})(?!\\d)",
                    Pattern.CASE_INSENSITIVE),
            // PHP / Laravel: "Server running on [http://127.0.0.1:8000]."
            Pattern.compile(
                    "\\bserver\\s+running\\s+on\\s+\\[?https?://[^\\s\\]\\\\]+:(\\d{2,5})(?!\\d)",
                    Pattern.CASE_INSENSITIVE),
            // Uvicorn: "Uvicorn running on http://127.0.0.1:8000"
            Pattern.compile(
                    "\\buvicorn\\s+running\\s+on\\s+https?://[^\\s:]+:(\\d{2,5})(?!\\d)",
                    Pattern.CASE_INSENSITIVE));

    /** URLs a localhost: señal débil, se prueba solo como último recurso. */
    private static final Pattern URL_HINT = Pattern.compile(
            "https?://(?:localhost|127\\.0\\.0\\.1|0\\.0\\.0\\.0|\\[::1\\]):(\\d{2,5})(?!\\d)");

    private final Set<Integer> explicitPorts = new LinkedHashSet<>();
    private final Set<Integer> urlHintPorts = new LinkedHashSet<>();

    /**
     * Analiza una línea de salida del proceso y registra el puerto si aparece.
     * Se invoca desde el hilo que drena el {@code stdout}/{@code stderr}, con
     * la línea ya normalizada (sin códigos de color ANSI).
     *
     * @param line una línea de salida, ya limpiada de códigos ANSI
     */
    public void observe(String line) {
        if (line == null || line.isBlank()) {
            return;
        }

        Integer explicit = firstMatch(EXPLICIT_PATTERNS, line);
        if (explicit != null) {
            remember(explicitPorts, explicit);
            return;
        }

        Integer hint = firstMatch(List.of(URL_HINT), line);
        if (hint != null) {
            remember(urlHintPorts, hint);
        }
    }

    /**
     * Puertos declarados explícitamente por el servidor, del más reciente al
     * más antiguo. Si el proceso se reinició y cambió de puerto, el más
     * reciente es el válido.
     *
     * @return lista de puertos explícitos, vacía si el proceso no dijo nada
     */
    public synchronized List<Integer> explicitPorts() {
        return mostRecentFirst(explicitPorts);
    }

    /**
     * Puertos deducidos de URLs a localhost, del más reciente al más antiguo.
     *
     * @return lista de pistas, vacía si no se vio ninguna URL
     */
    public synchronized List<Integer> urlHintPorts() {
        return mostRecentFirst(urlHintPorts);
    }

    private Integer firstMatch(List<Pattern> patterns, String line) {
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.find()) {
                Integer port = validPort(matcher.group(1));
                if (port != null) {
                    return port;
                }
            }
        }
        return null;
    }

    private Integer validPort(String raw) {
        int port;
        try {
            port = Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return null;
        }
        return port >= MIN_PORT && port <= MAX_PORT ? port : null;
    }

    private synchronized void remember(Set<Integer> target, int port) {
        // Reinsertar deja el puerto más reciente al final, que es justo lo que
        // aprovecha mostRecentFirst().
        target.remove(port);
        target.add(port);
        while (target.size() > MAX_TRACKED) {
            Iterator<Integer> oldest = target.iterator();
            oldest.next();
            oldest.remove();
        }
    }

    private List<Integer> mostRecentFirst(Set<Integer> ports) {
        List<Integer> ordered = new ArrayList<>(ports);
        Collections.reverse(ordered);
        return List.copyOf(ordered);
    }
}
