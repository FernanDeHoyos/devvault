package com.devvault.runtime.infrastructure;

/**
 * Docker Compose no es parte del Docker Engine API (lo que probamos en el
 * spike) — es una capa de orquestación aparte, expuesta como plugin de CLI.
 * Por eso, para start/stop invocamos el comando `docker compose` como
 * proceso externo, igual que lo hacen herramientas reales (ej. Portainer).
 */

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Component
public class DockerComposeRunner {

    private static final Duration TIMEOUT = Duration.ofMinutes(3);

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

        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ProcessResult(-1, "Timeout esperando 'docker compose " + composeArgs[0] + "'");
            }

            return new ProcessResult(process.exitValue(), output);

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ProcessResult(-1, "Error ejecutando docker compose: " + e.getMessage());
        }
    }
}
