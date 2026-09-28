package com.devvault.runtime.infrastructure;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Última línea de defensa para descubrir el puerto real de un proceso local:
 * pregunta al sistema operativo qué sockets TCP están en estado <em>LISTEN</em>
 * y a qué PID pertenecen.
 *
 * <p>Los regex sobre la salida del proceso son frágiles por naturaleza. Si el
 * proyecto manda sus logs a un archivo, si la build tool se traga la salida, si
 * el contenedor web cambia el texto de su mensaje, o si el arranque tarda más que
 * el timeout, no hay nada que parsear y el puerto se pierde. El sistema
 * operativo, en cambio, no miente: si el proceso tiene un socket abierto, está
 * abierto. Por eso esto se consulta solo cuando lo más barato no funcionó.
 *
 * <p>Se filtra por el árbol de PIDs (el proceso lanzado y sus descendientes)
 * para no confundir un puerto ajeno —el PostgreSQL de DevVault, otro proyecto
 * abierto en paralelo— con el de este proyecto. En Windows el build tool
 * encadena varios procesos ({@code cmd.exe -> mvnw.cmd -> java}) y el JVM de la
 * aplicación suele ser un descendiente lejano del que se lanzó, por eso se
 * recorre todo el árbol.
 *
 * <p>En Windows usa {@code netstat -ano -p tcp}; en Linux y macOS,
 * {@code lsof -nP -iTCP -sTCP:LISTEN}. Si la utilidad no existe, devuelve una
 * lista vacía y el resto del health check sigue funcionando igual.
 */
@Component
public class LocalPortProbe {

    private static final Logger log = LoggerFactory.getLogger(LocalPortProbe.class);

    private static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(5);
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;

    /** {@code LISTENING} en netstat, {@code (LISTEN)} en lsof. */
    private static final Pattern LISTENING_ROW = Pattern.compile("LISTEN", Pattern.CASE_INSENSITIVE);
    /** Columna de dirección local: {@code 0.0.0.0:8080}, {@code [::]:8080}, {@code 127.0.0.1:8080}. */
    private static final Pattern LOCAL_ADDRESS = Pattern.compile("^.*:(\\d{1,5})$");
    private static final Pattern PID_TOKEN = Pattern.compile("^\\d+$");

    /**
     * Devuelve los puertos TCP en escucha del proceso indicado y de todos sus
     * descendientes.
     *
     * @param root proceso raíz lanzado por DevVault
     * @return puertos en escucha, vacía si el proceso murió o la utilidad del
     *         sistema no está disponible
     */
    public List<Integer> listeningPorts(ProcessHandle root) {
        Set<Long> pids = processTree(root);
        if (pids.isEmpty()) {
            return List.of();
        }

        List<Integer> ports = isWindows() ? fromNetstat(pids) : fromLsof(pids);
        if (ports.isEmpty()) {
            log.debug("El SO no reportó puertos en escucha para los PIDs {}", pids);
        } else {
            log.info("Puertos en escucha del proceso (PIDs {}): {}", pids, ports);
        }
        return ports;
    }

    private List<Integer> fromNetstat(Set<Long> pids) {
        return parse(run("netstat", "-ano", "-p", "tcp"), pids);
    }

    private List<Integer> fromLsof(Set<Long> pids) {
        String pidList = pids.stream().map(String::valueOf).collect(Collectors.joining(","));
        return parse(run("lsof", "-nP", "-iTCP", "-sTCP:LISTEN", "-a", "-p", pidList), pids);
    }

    private Set<Long> processTree(ProcessHandle root) {
        Set<Long> pids = new LinkedHashSet<>();
        if (root == null || !root.isAlive()) {
            return pids;
        }
        pids.add(root.pid());
        root.descendants().forEach(handle -> pids.add(handle.pid()));
        return pids;
    }

    /**
     * Filtra las filas de netstat/lsof que corresponden a nuestros PIDs y extrae
     * el puerto local de cada una.
     */
    private List<Integer> parse(List<String> lines, Set<Long> pids) {
        if (lines == null) {
            return List.of();
        }

        Set<Integer> ports = new LinkedHashSet<>();
        for (String line : lines) {
            if (line.isBlank() || !LISTENING_ROW.matcher(line).find()) {
                continue;
            }
            String[] tokens = line.trim().split("\\s+");
            if (tokens.length < 3) {
                continue;
            }
            Long pid = extractPid(tokens);
            if (pid == null || !pids.contains(pid)) {
                continue;
            }
            Integer port = extractLocalPort(tokens);
            if (port != null) {
                ports.add(port);
            }
        }
        return List.copyOf(ports);
    }

    /**
     * El PID no está en la misma columna según la utilidad: en netstat es la
     * última de la fila, en lsof va justo después de {@code (LISTEN)}.
     */
    private Long extractPid(String[] tokens) {
        for (int i = 0; i < tokens.length; i++) {
            if (tokens[i].contains("(LISTEN)")) {
                if (i + 1 < tokens.length && PID_TOKEN.matcher(tokens[i + 1]).matches()) {
                    return Long.parseLong(tokens[i + 1]);
                }
                return null;
            }
        }
        String last = tokens[tokens.length - 1];
        return PID_TOKEN.matcher(last).matches() ? Long.parseLong(last) : null;
    }

    private Integer extractLocalPort(String[] tokens) {
        // Se empieza en 1 porque la columna 0 es el protocolo (netstat) o el
        // nombre del comando (lsof), nunca una dirección.
        for (int i = 1; i < tokens.length; i++) {
            Matcher matcher = LOCAL_ADDRESS.matcher(tokens[i]);
            if (matcher.matches()) {
                int port = Integer.parseInt(matcher.group(1));
                if (port >= MIN_PORT && port <= MAX_PORT) {
                    return port;
                }
            }
        }
        return null;
    }

    /**
     * Ejecuta un comando corto y devuelve su salida. La lectura va en un hilo
     * aparte para que un comando colgado no bloquee el health check, y el
     * proceso se destruye siempre al terminar.
     */
    private List<String> run(String... command) {
        Process process;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
        } catch (IOException e) {
            log.debug("No se pudo ejecutar {}: {}", command[0], e.getMessage());
            return null;
        }

        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        Thread reader = new Thread(() -> {
            try (BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    lines.add(line);
                }
            } catch (IOException ignored) {
                // El proceso se cerró: no hay más salida que leer.
            }
        }, "local-port-probe-reader");
        reader.setDaemon(true);
        reader.start();

        try {
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                log.debug("{} excedió {}s y se abortó", command[0], COMMAND_TIMEOUT.toSeconds());
                return null;
            }
            reader.join(500);
            return List.copyOf(lines);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
