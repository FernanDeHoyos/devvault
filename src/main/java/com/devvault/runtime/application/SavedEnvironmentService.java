package com.devvault.runtime.application;

import com.devvault.discovery.application.ProjectLookupService;
import com.devvault.runtime.domain.RuntimeStatus;
import com.devvault.runtime.infrastructure.RuntimeInstanceRepository;
import com.devvault.shared.api.exception.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Service
public class SavedEnvironmentService {
    public record Definition(String name, List<EnvironmentPlan.Entry> projects) {}
    public record Environment(UUID id, UUID workspaceId, String name, List<EnvironmentPlan.Entry> projects) {}
    public record Step(UUID projectId, String status, String message) {}
    public record Run(UUID id, UUID environmentId, String operation, String status, Instant createdAt, List<Step> steps) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProjectLookupService projects;
    private final StartProjectUseCase start;
    private final StopProjectUseCase stop;
    private final RuntimeInstanceRepository runtimes;
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(task -> daemon(task, "environment-coordinator"));
    private final ExecutorService workers = Executors.newFixedThreadPool(4, task -> daemon(task, "environment-worker"));
    private boolean busy;

    public SavedEnvironmentService(JdbcTemplate jdbc, ObjectMapper json, ProjectLookupService projects,
            StartProjectUseCase start, StopProjectUseCase stop, RuntimeInstanceRepository runtimes) {
        this.jdbc = jdbc; this.json = json; this.projects = projects;
        this.start = start; this.stop = stop; this.runtimes = runtimes;
    }
    public List<Environment> list(UUID workspace) {
        return jdbc.query("SELECT id, workspace_id, definition FROM saved_environments WHERE workspace_id = ? ORDER BY name",
                (rs, row) -> environment(rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class), rs.getString("definition")), workspace);
    }
    private Environment environment(UUID id, UUID workspace, String value) {
        Definition definition = decode(value, Definition.class);
        return new Environment(id, workspace, definition.name(), definition.projects());
    }
    public Environment get(UUID workspace, UUID id) {
        return list(workspace).stream().filter(e -> e.id().equals(id)).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Entorno no encontrado."));
    }
    public synchronized Environment save(UUID workspace, UUID id, Definition definition) {
        if (busy) throw new ApiException(HttpStatus.CONFLICT, "Espera a que termine la operación de entorno.");
        if (definition == null || definition.name() == null || definition.name().isBlank() || definition.name().length() > 120)
            throw new ApiException(HttpStatus.BAD_REQUEST, "Escribe un nombre de hasta 120 caracteres.");
        validate(workspace, definition.projects());
        var clean = new Definition(definition.name().trim(), definition.projects());
        if (id == null) {
            id = UUID.randomUUID();
            jdbc.update("INSERT INTO saved_environments(id, workspace_id, name, definition) VALUES (?, ?, ?, ?)", id, workspace, clean.name(), encode(clean));
        } else {
            get(workspace, id);
            jdbc.update("UPDATE saved_environments SET name = ?, definition = ? WHERE id = ?", clean.name(), encode(clean), id);
        }
        return new Environment(id, workspace, clean.name(), clean.projects());
    }
    public synchronized void delete(UUID workspace, UUID id) {
        if (busy) throw new ApiException(HttpStatus.CONFLICT, "Espera a que termine la operación de entorno.");
        get(workspace, id);
        jdbc.update("DELETE FROM saved_environments WHERE id = ?", id);
    }
    private List<List<UUID>> validate(UUID workspace, List<EnvironmentPlan.Entry> entries) {
        try {
            var layers = EnvironmentPlan.layers(entries);
            var members = new HashSet<>(projects.findIdsByWorkspaceId(workspace));
            if (entries.stream().anyMatch(e -> !members.contains(e.projectId()))) throw new IllegalArgumentException("Hay proyectos que ya no pertenecen al workspace.");
            return layers;
        } catch (IllegalArgumentException e) { throw new ApiException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }
    public List<Run> history(UUID workspace, UUID id) {
        get(workspace, id);
        return jdbc.query("SELECT report FROM environment_runs WHERE environment_id = ? ORDER BY created_at DESC LIMIT 30",
                (rs, row) -> decode(rs.getString(1), Run.class), id);
    }
    public synchronized Run execute(UUID workspace, UUID id, String operation) {
        if (operation == null || !Set.of("START", "STOP").contains(operation)) throw new ApiException(HttpStatus.BAD_REQUEST, "Operación inválida.");
        if (busy) throw new ApiException(HttpStatus.CONFLICT, "Ya hay una operación de entorno en curso.");
        var environment = get(workspace, id);
        var layers = new ArrayList<>(validate(workspace, environment.projects()));
        if (operation.equals("STOP")) Collections.reverse(layers);
        var run = new Run(UUID.randomUUID(), id, operation, "RUNNING", Instant.now(),
                environment.projects().stream().map(e -> new Step(e.projectId(), "WAITING", null)).toList());
        jdbc.update("INSERT INTO environment_runs(id, environment_id, status, report, created_at) VALUES (?, ?, ?, ?, ?)",
                run.id(), id, run.status(), encode(run), java.sql.Timestamp.from(run.createdAt()));
        busy = true;
        try { coordinator.submit(() -> process(run, layers)); }
        catch (RejectedExecutionException e) {
            busy = false;
            publish(run, "INTERRUPTED", run.steps());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DevVault se está cerrando.");
        }
        return run;
    }
    private void process(Run run, List<List<UUID>> layers) {
        Map<UUID, Step> steps = new LinkedHashMap<>();
        run.steps().forEach(step -> steps.put(step.projectId(), step));
        try {
            for (List<UUID> layer : layers) {
                layer.forEach(id -> steps.put(id, new Step(id, "RUNNING", null)));
                publish(run, "RUNNING", List.copyOf(steps.values()));
                List<CompletableFuture<Step>> pending = layer.stream().map(id ->
                        CompletableFuture.supplyAsync(() -> operate(id, run.operation()), workers)).toList();
                // Wait for the whole dependency layer before starting dependents or stopping dependencies.
                for (var future : pending) { Step result = future.join(); steps.put(result.projectId(), result); }
                if (steps.values().stream().anyMatch(s -> s.status().equals("FAILED"))) {
                    steps.replaceAll((id, step) -> step.status().equals("WAITING") ? new Step(id, "SKIPPED", "Una dependencia o servicio dependiente falló.") : step);
                    publish(run, "FAILED", List.copyOf(steps.values()));
                    return;
                }
            }
            publish(run, "COMPLETED", List.copyOf(steps.values()));
        } catch (RuntimeException e) {
            steps.replaceAll((id, step) -> Set.of("RUNNING", "WAITING").contains(step.status()) ? new Step(id, "INTERRUPTED", "Operación interrumpida; comprueba el estado antes de reintentar.") : step);
            publish(run, "INTERRUPTED", List.copyOf(steps.values()));
        } finally { synchronized (this) { busy = false; } }
    }
    private Step operate(UUID id, String operation) {
        try {
            var previous = runtimes.findFirstByProjectIdOrderByStartedAtDesc(id);
            if (operation.equals("START")) {
                if (previous.isPresent() && previous.get().getOverallStatus() == RuntimeStatus.RUNNING)
                    return new Step(id, "READY", "Ya estaba en ejecución.");
                var instance = start.execute(id);
                return new Step(id, instance.getOverallStatus() == RuntimeStatus.RUNNING ? "READY" : "FAILED", null);
            }
            if (previous.isEmpty() || previous.get().getOverallStatus() == RuntimeStatus.STOPPED)
                return new Step(id, "STOPPED", "Ya estaba detenido.");
            var instance = stop.execute(id);
            return new Step(id, instance.getOverallStatus() == RuntimeStatus.STOPPED ? "STOPPED" : "FAILED", null);
        } catch (ApiException e) { return new Step(id, "FAILED", e.getMessage()); }
        catch (RuntimeException e) { return new Step(id, "FAILED", "No se pudo completar la operación. Revisa el diagnóstico del proyecto."); }
    }
    private void publish(Run run, String status, List<Step> steps) {
        jdbc.update("UPDATE environment_runs SET status = ?, report = ? WHERE id = ?", status,
                encode(new Run(run.id(), run.environmentId(), run.operation(), status, run.createdAt(), steps)), run.id());
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterrupted() {
        var interrupted = jdbc.query("SELECT report FROM environment_runs WHERE status = 'RUNNING'", (rs, row) -> decode(rs.getString(1), Run.class));
        for (var run : interrupted) publish(run, "INTERRUPTED", run.steps().stream().map(step ->
                Set.of("WAITING", "RUNNING").contains(step.status()) ? new Step(step.projectId(), "INTERRUPTED", "DevVault se reinició; revisa el estado y reintenta.") : step).toList());
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("No se pudo guardar el entorno.", e); }
    }
    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("No se pudo leer el entorno guardado.", e); }
    }
    @PreDestroy public void close() { coordinator.shutdownNow(); workers.shutdownNow(); }
    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }
}
