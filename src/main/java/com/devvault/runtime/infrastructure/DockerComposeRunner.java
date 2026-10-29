package com.devvault.runtime.infrastructure;

/**
 * Docker Compose no es parte del Docker Engine API (lo que probamos en el
 * spike) — es una capa de orquestación aparte, expuesta como plugin de CLI.
 * Por eso, para start/stop invocamos el comando `docker compose` como
 * proceso externo, igual que lo hacen herramientas reales (ej. Portainer).
 */

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
public class DockerComposeRunner {

    private static final Duration TIMEOUT = Duration.ofMinutes(10);
    private static final List<String> COMPOSE_FILENAMES = List.of(
            "compose.yaml", "compose.yml", "docker-compose.yaml", "docker-compose.yml");

    /** Encuentra el nombre de archivo Compose preferido por la especificación actual. */
    public Optional<Path> findComposeFile(Path projectDirectory) {
        return COMPOSE_FILENAMES.stream()
                .map(projectDirectory::resolve)
                .filter(java.nio.file.Files::isRegularFile)
                .findFirst();
    }

    public record ProcessResult(int exitCode, String output) {
        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    public ProcessResult up(Path composeFile, String projectName) {
        return run(composeFile, projectName, "up", "-d");
    }

    /**
     * Detiene los contenedores del stack de Docker Compose.
     * 
     * @param composeFile Path del archivo docker-compose.yml
     * @param projectName Nombre del proyecto de Docker Compose
     * @return Resultado del proceso
     */
    public ProcessResult down(Path composeFile, String projectName) {
        return run(composeFile, projectName, "down");
    }

    /**
     * Ejecuta un comando de Docker Compose.
     * 
     * @param composeFile Path del archivo docker-compose.yml
     * @param projectName Nombre del proyecto de Docker Compose
     * @param composeArgs Argumentos del comando
     * @return Resultado del proceso
     */
    private ProcessResult run(Path composeFile, String projectName, String... composeArgs) {
        List<String> command = new java.util.ArrayList<>(List.of(
                "docker", "compose", "-p", projectName, "-f", composeFile.toString()));
        command.addAll(List.of(composeArgs));

        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            Process startedProcess = process;
            CompletableFuture<String> output = CompletableFuture.supplyAsync(
                    () -> readOutput(startedProcess.getInputStream()));

            boolean finished = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                terminateTree(process);
                return new ProcessResult(-1, "Timeout esperando 'docker compose " + composeArgs[0] + "'.\n"
                        + awaitOutput(output));
            }

            return new ProcessResult(process.exitValue(), awaitOutput(output));

        } catch (IOException e) {
            return new ProcessResult(-1, "Error ejecutando docker compose: " + e.getMessage());
        } catch (InterruptedException e) {
            if (process != null) {
                terminateTree(process);
            }
            Thread.currentThread().interrupt();
            return new ProcessResult(-1, "Error ejecutando docker compose: " + e.getMessage());
        }
    }

    private String readOutput(InputStream input) {
        try (input) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "No se pudo leer la salida de Docker Compose: " + e.getMessage();
        }
    }

    private String awaitOutput(CompletableFuture<String> output) {
        try {
            return output.get(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "Lectura de salida interrumpida.";
        } catch (ExecutionException | TimeoutException e) {
            return "La salida de Docker Compose no terminó de recibirse.";
        }
    }

    private void terminateTree(Process process) {
        process.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
