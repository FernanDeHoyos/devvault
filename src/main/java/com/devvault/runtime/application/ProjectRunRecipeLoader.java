package com.devvault.runtime.application;

import com.devvault.discovery.plugin.RunConfiguration;
import com.devvault.shared.api.exception.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Loads a portable, project-owned local run recipe without invoking a shell. */
@Component
public class ProjectRunRecipeLoader {

    public static final String FILE_NAME = "devvault.run.json";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public Optional<RunConfiguration> load(Path projectDirectory) {
        Path recipeFile = projectDirectory.resolve(FILE_NAME);
        if (!Files.isRegularFile(recipeFile)) {
            return Optional.empty();
        }

        try {
            JsonNode root = objectMapper.readTree(recipeFile.toFile());
            if (root.path("version").asInt(-1) != 1) {
                throw invalid("La receta debe declarar version: 1.");
            }
            JsonNode service = root.path("service");
            String name = requiredText(service, "name");
            List<String> command = commandForCurrentPlatform(service);
            Integer port = optionalPort(service);
            int timeout = service.path("startupTimeoutSeconds").asInt(DEFAULT_TIMEOUT_SECONDS);
            if (timeout < 5 || timeout > 1800) {
                throw invalid("startupTimeoutSeconds debe estar entre 5 y 1800.");
            }
            Path workingDirectory = resolveWorkingDirectory(projectDirectory,
                    service.path("workingDirectory").asText("."));
            Map<String, String> environment = readEnvironment(service.path("environment"));
            String healthCheckUrl = readHealthCheckUrl(service.path("healthCheckUrl"));
            return Optional.of(new RunConfiguration(name, command, port, timeout,
                    workingDirectory, environment, healthCheckUrl));
        } catch (ApiException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException apiException) throw apiException;
            throw invalid("No se pudo leer " + FILE_NAME + ": " + e.getMessage());
        }
    }

    private List<String> commandForCurrentPlatform(JsonNode service) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        JsonNode command = windows && service.has("windowsCommand")
                ? service.path("windowsCommand") : service.path("command");
        if (!command.isArray() || command.isEmpty()) {
            throw invalid("service.command debe ser una lista de argumentos no vacía.");
        }
        List<String> arguments = new ArrayList<>();
        command.forEach(value -> {
            if (!value.isTextual() || value.asText().isBlank()) {
                throw invalid("Cada argumento del comando debe ser texto no vacío.");
            }
            arguments.add(value.asText());
        });
        return List.copyOf(arguments);
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty() || value.length() > 80) {
            throw invalid("service." + field + " es obligatorio y admite hasta 80 caracteres.");
        }
        return value;
    }

    private Integer optionalPort(JsonNode service) {
        if (!service.hasNonNull("port")) return null;
        int port = service.path("port").asInt(-1);
        if (port < 1 || port > 65535) throw invalid("service.port debe estar entre 1 y 65535.");
        return port;
    }

    private Path resolveWorkingDirectory(Path projectDirectory, String relativePath) {
        try {
            Path root = projectDirectory.toRealPath();
            Path workingDirectory = root.resolve(relativePath).normalize();
            if (!Files.isDirectory(workingDirectory)) {
                throw invalid("service.workingDirectory debe ser una carpeta existente dentro del proyecto.");
            }
            workingDirectory = workingDirectory.toRealPath();
            if (!workingDirectory.startsWith(root)) {
                throw invalid("service.workingDirectory debe estar dentro del proyecto.");
            }
            return workingDirectory;
        } catch (IOException e) {
            throw invalid("service.workingDirectory debe ser una carpeta existente dentro del proyecto.");
        }
    }

    private Map<String, String> readEnvironment(JsonNode environmentNode) {
        if (!environmentNode.isMissingNode() && !environmentNode.isObject()) {
            throw invalid("service.environment debe ser un objeto de valores de texto.");
        }
        Map<String, String> environment = new LinkedHashMap<>();
        environmentNode.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) {
                throw invalid("Las variables de entorno de la receta deben tener valores de texto.");
            }
            environment.put(entry.getKey(), entry.getValue().asText());
        });
        return Map.copyOf(environment);
    }

    private String readHealthCheckUrl(JsonNode node) {
        if (node.isMissingNode() || node.isNull() || node.asText().isBlank()) return null;
        if (!node.isTextual()) throw invalid("service.healthCheckUrl debe ser una URL HTTP local.");
        try {
            URI uri = new URI(node.asText());
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(java.util.Locale.ROOT);
            if (!"http".equalsIgnoreCase(uri.getScheme())
                    || !(host.equals("localhost") || host.equals("127.0.0.1")
                            || host.equals("::1") || host.equals("[::1]"))
                    || uri.getUserInfo() != null) {
                throw invalid("service.healthCheckUrl debe usar HTTP y apuntar a localhost o loopback.");
            }
            return uri.toString();
        } catch (URISyntaxException e) {
            throw invalid("service.healthCheckUrl no es una URL válida.");
        }
    }

    private ApiException invalid(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, message);
    }
}
