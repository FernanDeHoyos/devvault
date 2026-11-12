package com.devvault.runtime.application;

import com.devvault.discovery.application.ProjectLookupService;
import com.devvault.discovery.plugin.RunConfiguration;
import com.devvault.runtime.infrastructure.DockerComposeRunner;
import com.devvault.runtime.infrastructure.LocalPortProbe;
import com.devvault.shared.api.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Service
public class StartupDiagnostics {
    private static final boolean WINDOWS = File.separatorChar == '\\';
    private static final Set<String> JAVA_BUILD_TOOLS = Set.of("mvn", "mvnw", "gradle", "gradlew");
    private static final Set<String> NODE_BUILD_TOOLS = Set.of("npm", "npx", "pnpm", "yarn");

    private final ProjectLookupService projects;
    private final LocalRunConfigurationResolver resolver;
    private final DockerComposeRunner compose;
    private final LocalPortProbe ports;

    public StartupDiagnostics(ProjectLookupService projects, LocalRunConfigurationResolver resolver,
            DockerComposeRunner compose, LocalPortProbe ports) {
        this.projects = projects;
        this.resolver = resolver;
        this.compose = compose;
        this.ports = ports;
    }

    public record Check(String level, String message) {}
    public record Report(String mode, List<Check> checks) {}

    public Report inspect(UUID id) {
        var project = projects.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Proyecto no encontrado."));
        Path root = Path.of(project.path());
        if (!Files.isDirectory(root)) {
            return new Report(null, List.of(new Check("ERROR", "La carpeta del proyecto no está disponible.")));
        }
        if (compose.findComposeFile(root).isPresent()) {
            return inspectCompose();
        }

        RunConfiguration configuration;
        try {
            configuration = resolver.resolve(root);
        } catch (ApiException e) {
            return new Report("LOCAL", List.of(new Check("ERROR",
                    "Revisa devvault.run.json o agrega una receta de arranque válida para este proyecto.")));
        }
        List<Check> checks = new ArrayList<>();
        Path directory = configuration.workingDirectory() == null ? root : configuration.workingDirectory();
        Map<String, String> environment = new ProcessBuilder(configuration.command()).environment();
        environment.putAll(configuration.environment());
        inspectTools(configuration.command(), directory, environment, checks);
        configuration.missingEnvironment(environment).forEach(name -> checks.add(new Check("ERROR",
                "Falta " + name + ". Defínela en el entorno de DevVault o en la receta de arranque.")));
        inspectPort(configuration.port(), checks);
        return new Report("LOCAL", List.copyOf(checks));
    }

    private void inspectTools(List<String> command, Path directory, Map<String, String> environment,
            List<Check> checks) {
        if (!isExecutable(command.get(0), directory, System.getenv("PATH"))) {
            checks.add(new Check("ERROR", "No se encuentra el ejecutable de arranque. Revisa la receta y el PATH de DevVault."));
            return;
        }
        String launcher = command.get(0);
        if (WINDOWS && launcher.equalsIgnoreCase("cmd.exe") && command.size() > 2
                && command.get(1).equalsIgnoreCase("/c") && command.get(2).matches("[A-Za-z0-9_.\\\\/-]+")) {
            launcher = command.get(2);
            if (!isExecutable(launcher, directory, environmentValue(environment, "PATH"))) {
                checks.add(new Check("ERROR", "No se encuentra el comando que debe ejecutar cmd.exe."));
            }
        }
        Path filename = Path.of(launcher).getFileName();
        String tool = filename == null ? "" : filename.toString().toLowerCase(Locale.ROOT)
                .replaceFirst("\\.(exe|cmd|bat)$", "");
        if (JAVA_BUILD_TOOLS.contains(tool) && !hasJava(directory, environment)) {
            checks.add(new Check("ERROR", "Java no está disponible. Revisa JAVA_HOME y el PATH."));
        }
        if (NODE_BUILD_TOOLS.contains(tool) && !isExecutable("node", directory, environmentValue(environment, "PATH"))) {
            checks.add(new Check("ERROR", "Node.js no está disponible en el PATH."));
        }
    }

    private boolean hasJava(Path directory, Map<String, String> environment) {
        String javaHome = environmentValue(environment, "JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            try {
                return isExecutable(Path.of(javaHome, "bin", WINDOWS ? "java.exe" : "java").toString(),
                        directory, null);
            } catch (InvalidPathException e) {
                return false;
            }
        }
        return isExecutable("java", directory, environmentValue(environment, "PATH"));
    }

    private String environmentValue(Map<String, String> environment, String name) {
        String exact = environment.get(name);
        if (exact != null || !WINDOWS) {
            return exact;
        }
        return environment.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    private boolean isExecutable(String command, Path directory, String path) {
        try {
            Path executable = Path.of(command);
            if (executable.isAbsolute() || command.contains("/") || command.contains("\\")) {
                return executableExists(directory.resolve(executable));
            }
            if (WINDOWS && executableExists(directory.resolve(executable))) {
                return true;
            }
            if (path != null) {
                for (String entry : path.split(Pattern.quote(File.pathSeparator))) {
                    if (!entry.isBlank() && executableExists(directory.resolve(entry.replace("\"", "")).resolve(executable))) {
                        return true;
                    }
                }
            }
        } catch (InvalidPathException e) {
            return false;
        }
        return false;
    }

    private boolean executableExists(Path file) {
        return WINDOWS
                ? Files.isRegularFile(file) || Files.isRegularFile(file.resolveSibling(file.getFileName() + ".exe"))
                : Files.isRegularFile(file) && Files.isExecutable(file);
    }

    private void inspectPort(Integer port, List<Check> checks) {
        if (port == null || port <= 0) {
            return;
        }
        var scan = ports.scanListeners(Set.of(port));
        if (!scan.available()) {
            checks.add(new Check("WARNING", "No se pudo comprobar el puerto " + port + "."));
        } else if (!scan.listenersByPort().getOrDefault(port, List.of()).isEmpty()) {
            checks.add(new Check("WARNING", "El puerto " + port + " está ocupado. Libéralo o cambia el puerto de arranque."));
        }
    }

    private Report inspectCompose() {
        Check check = null;
        if (!isExecutable("docker", Path.of("").toAbsolutePath(), System.getenv("PATH"))) {
            check = new Check("ERROR", "Docker no está disponible en el PATH de DevVault.");
        } else if (!dockerResponds("compose", "version")) {
            check = new Check("ERROR", "Docker Compose no está disponible. Comprueba la instalación de Docker.");
        } else if (!dockerResponds("info", "--format", "{{.ServerVersion}}")) {
            check = new Check("ERROR", "Docker no responde. Abre Docker Desktop o comprueba el acceso al motor.");
        }
        return new Report("COMPOSE", check == null ? List.of() : List.of(check));
    }

    private boolean dockerResponds(String... arguments) {
        List<String> command = new ArrayList<>(List.of("docker"));
        command.addAll(List.of(arguments));
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(4, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }
}
