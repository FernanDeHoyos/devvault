package com.devvault.runtime.application;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.devvault.runtime.infrastructure.LocalPortProbe;

/**
 * Confirma que un proceso local llegó a estar sirviendo de verdad, y devuelve
 * el puerto por el que lo hizo.
 *
 * <p>El orden de las comprobaciones va de lo barato y fiable a lo caro:
 *
 * <ol>
 *   <li><b>Puertos explícitos</b> leídos de la salida del proceso
 *       ({@code Tomcat started on port 8080}). Son los que el propio servidor
 *       declara, así que son la señal más fuerte.</li>
 *   <li><b>Puerto configurado</b> por el plugin. Es el respaldo que el diseño
 *       documenta desde el principio: si el proceso nunca dice por qué puerto
 *       escucha, se prueba el que el plugin calculó al leer
 *       {@code application.yml} o {@code application.properties}. Antes este
 *       valor se recibía y se ignoraba, y el resultado era que un proyecto
 *       perfectamente sano siempre acababa marcado como FAILED.</li>
 *   <li><b>Puertos en escucha según el sistema operativo</b>, filtrados por el
 *       árbol de PIDs. Es la verdad de terreno y cubre el caso en que la salida
 *       del proceso no dice absolutamente nada (log a archivo, arranque muy
 *       lento, build tool que se traga la salida).</li>
 *   <li><b>Pistas por URL</b> a localhost. Débiles, se prueban al final.</li>
 * </ol>
 *
 * <p>Los motivos de fallo distinguen las tres situaciones que antes se
 * reportaban todas como el mismo error genérico: el proceso murió, el proceso
 * vive pero no abrió ningún puerto, o el puerto se encontró pero no acepta
 * conexiones.
 */
@Component
public class LocalProcessHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(LocalProcessHealthProbe.class);

    private static final long POLL_INTERVAL_MS = 500;
    private static final long OS_PROBE_INTERVAL_MS = 3_000;
    private static final int CONNECT_TIMEOUT_MS = 1_000;
    private static final int REASON_TAIL_LINES = 12;
    private static final int REASON_LINE_LIMIT = 220;

    private final LocalPortProbe osPortProbe;

    public LocalProcessHealthProbe(LocalPortProbe osPortProbe) {
        this.osPortProbe = osPortProbe;
    }

    /**
     * @param healthy      si el proceso quedó sirviendo
     * @param port         puerto detectado, o {@code null} si no se encontró
     * @param failureReason motivo legible del fallo, {@code null} si es healthy
     */
    public record Result(boolean healthy, Integer port, String failureReason) {
    }

    /**
     * Espera a que el proceso abra un puerto y responda.
     *
     * @param process         proceso local ya lanzado
     * @param signals         señales leídas de su salida, alimentadas en paralelo
     * @param configuredPort  puerto que calculó el plugin, o {@code null}/0 si
     *                        el proyecto usa un puerto dinámico
     * @param timeoutSeconds  tiempo máximo de espera
     * @param recentLines     acceso al buffer de logs, para adjuntar el motivo
     * @return resultado con el puerto detectado o el motivo del fallo
     */
    public Result awaitReady(Process process, PortSignals signals, Integer configuredPort,
            int timeoutSeconds, Supplier<List<String>> recentLines) {

        long deadline = System.currentTimeMillis() + Math.max(5, timeoutSeconds) * 1000L;
        List<Integer> osPorts = List.of();
        long nextOsProbeAt = 0L;

        while (true) {
            if (!process.isAlive()) {
                String reason = "El proceso local terminó (código de salida " + process.exitValue()
                        + ") antes de abrir un puerto." + tail(recentLines.get());
                return new Result(false, null, reason);
            }

            if (System.currentTimeMillis() >= deadline) {
                String reason = "El proceso local sigue vivo pero no abrió ningún puerto en "
                        + timeoutSeconds + "s. Asumido: " + describe(configuredPort)
                        + "; declarado en la salida: " + describe(signals.explicitPorts())
                        + "; en escucha según el SO: " + describe(osPorts) + "."
                        + tail(recentLines.get());
                return new Result(false, null, reason);
            }

            // 1. Lo que el servidor dijo explícitamente.
            for (int port : signals.explicitPorts()) {
                if (isPortOpen(port)) {
                    log.info("Health check OK en el puerto {} (declarado en la salida)", port);
                    return new Result(true, port, null);
                }
            }

            // 2. Respaldo: el puerto que el plugin calculó desde la config.
            if (isUsable(configuredPort) && isPortOpen(configuredPort)) {
                log.info("Health check OK en el puerto {} (puerto configurado)", configuredPort);
                return new Result(true, configuredPort, null);
            }

            // 3. La verdad del sistema operativo, como red de seguridad.
            if (System.currentTimeMillis() >= nextOsProbeAt) {
                osPorts = osPortProbe.listeningPorts(process.toHandle());
                nextOsProbeAt = System.currentTimeMillis() + OS_PROBE_INTERVAL_MS;
                for (int port : osPorts) {
                    if (isPortOpen(port)) {
                        log.info("Health check OK en el puerto {} (detectado por el SO)", port);
                        return new Result(true, port, null);
                    }
                }
            }

            // 4. Pistas débiles: URLs a localhost en algún log.
            for (int port : signals.urlHintPorts()) {
                if (isPortOpen(port)) {
                    log.info("Health check OK en el puerto {} (inferido de una URL local)", port);
                    return new Result(true, port, null);
                }
            }

            sleep();
        }
    }

    /**
     * Prueba el puerto en las tres familias de loopback. Probar solo
     * {@code localhost} no alcanza: en Windows y Linux puede resolver a
     * {@code ::1} (IPv6) primero y devolver "connection refused" aunque el
     * proceso esté sirviendo perfectamente en IPv4.
     */
    private boolean isPortOpen(int port) {
        for (String host : LOOPBACK_HOSTS) {
            if (tryConnect(host, port)) {
                return true;
            }
        }
        return false;
    }

    private boolean tryConnect(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (Exception e) {
            log.debug("No se pudo conectar a {}:{} ({})", host, port, e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean isUsable(Integer port) {
        return port != null && port > 0;
    }

    private String describe(Integer port) {
        return isUsable(port) ? String.valueOf(port) : "ninguno";
    }

    private String describe(List<Integer> ports) {
        return ports.isEmpty() ? "ninguno" : ports.toString();
    }

    /**
     * Recorta las últimas líneas de salida del proceso para que el motivo de
     * fallo diga qué estaba pasando de verdad, sin volcar el historial entero
     * en la base de datos.
     */
    private String tail(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return " Sin salida capturada.";
        }
        int from = Math.max(0, lines.size() - REASON_TAIL_LINES);
        String joined = lines.subList(from, lines.size()).stream()
                .map(line -> line.length() > REASON_LINE_LIMIT
                        ? line.substring(0, REASON_LINE_LIMIT) + "..."
                        : line)
                .reduce((a, b) -> a + " | " + b)
                .orElse("");
        return " Últimas líneas: " + joined;
    }

    private void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static final List<String> LOOPBACK_HOSTS = List.of("127.0.0.1", "::1", "localhost");
}
