package com.devvault.runtime.application;

import com.devvault.discovery.application.ProjectLookupService;
import com.devvault.runtime.domain.RuntimeStatus;
import com.devvault.shared.api.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class WorkspaceGroupStartup {
    public static final int MAX_PROJECTS = 25;
    private static final Duration REPORT_LIFETIME = Duration.ofHours(24);
    private static final Logger log = LoggerFactory.getLogger(WorkspaceGroupStartup.class);

    private final ProjectLookupService projects;
    private final StartProjectUseCase starter;
    private final TaskExecutor executor;
    private final ConcurrentHashMap<UUID, Run> runs = new ConcurrentHashMap<>();

    public WorkspaceGroupStartup(ProjectLookupService projects, StartProjectUseCase starter,
            @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
        this.projects = projects;
        this.starter = starter;
        this.executor = executor;
    }

    public enum State { STARTING, COMPLETED, FAILED }
    public enum StepState { WAITING, STARTING, READY, FAILED, SKIPPED }
    public record Step(UUID projectId, StepState status, String message) {}
    public record Report(UUID id, State status, List<Step> steps, Instant finishedAt) {}

    public Report start(UUID workspaceId, List<UUID> orderedIds) {
        validate(workspaceId, orderedIds);
        Instant cutoff = Instant.now().minus(REPORT_LIFETIME);
        runs.entrySet().removeIf(entry -> {
            Instant finishedAt = entry.getValue().report.finishedAt();
            return finishedAt != null && finishedAt.isBefore(cutoff);
        });

        var initial = new Report(UUID.randomUUID(), State.STARTING, orderedIds.stream()
                .map(id -> new Step(id, StepState.WAITING, null)).toList(), null);
        Run run = new Run(initial);
        runs.compute(workspaceId, (id, previous) -> {
            if (previous != null && previous.report.status() == State.STARTING) {
                throw new ApiException(HttpStatus.CONFLICT, "Ya hay un grupo iniciándose en este workspace.");
            }
            return run;
        });
        try {
            executor.execute(() -> execute(run));
        } catch (TaskRejectedException exception) {
            runs.remove(workspaceId, run);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DevVault no pudo programar el arranque. Intenta de nuevo.");
        }
        return initial;
    }

    public Report latest(UUID workspaceId) {
        Run run = runs.get(workspaceId);
        return run == null ? null : run.report;
    }

    private void validate(UUID workspaceId, List<UUID> orderedIds) {
        if (orderedIds == null || orderedIds.size() < 2 || orderedIds.size() > MAX_PROJECTS
                || orderedIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(orderedIds).size() != orderedIds.size()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Selecciona entre 2 y 25 proyectos distintos.");
        }
        Set<UUID> members = new HashSet<>(projects.findIdsByWorkspaceId(workspaceId));
        if (!members.containsAll(orderedIds)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Todos los proyectos deben pertenecer al workspace.");
        }
    }

    private void execute(Run run) {
        List<Step> steps = new ArrayList<>(run.report.steps());
        for (int index = 0; index < steps.size(); index++) {
            UUID projectId = steps.get(index).projectId();
            steps.set(index, new Step(projectId, StepState.STARTING, null));
            run.publish(State.STARTING, steps);
            String failure = startProject(projectId);
            if (failure != null) {
                steps.set(index, new Step(projectId, StepState.FAILED, failure));
                for (int skipped = index + 1; skipped < steps.size(); skipped++) {
                    steps.set(skipped, new Step(steps.get(skipped).projectId(), StepState.SKIPPED, null));
                }
                run.publish(State.FAILED, steps);
                return;
            }
            steps.set(index, new Step(projectId, StepState.READY, null));
        }
        run.publish(State.COMPLETED, steps);
    }

    private String startProject(UUID projectId) {
        try {
            var instance = starter.execute(projectId);
            if (instance.getOverallStatus() == RuntimeStatus.RUNNING) {
                return null;
            }
            return "El proyecto no alcanzó el estado listo. Abre su detalle para revisar el arranque.";
        } catch (ApiException exception) {
            return exception.getMessage();
        } catch (RuntimeException exception) {
            log.error("Falló el arranque de grupo para el proyecto {}", projectId, exception);
            return "DevVault no pudo iniciar este proyecto. Revisa sus logs.";
        }
    }

    private static final class Run {
        private volatile Report report;

        private Run(Report report) {
            this.report = report;
        }

        private void publish(State status, List<Step> steps) {
            report = new Report(report.id(), status, List.copyOf(steps),
                    status == State.STARTING ? null : Instant.now());
        }
    }
}
