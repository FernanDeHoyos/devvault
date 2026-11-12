package com.devvault.runtime.application;

import com.devvault.discovery.application.ProjectLookupService;
import com.devvault.runtime.domain.RuntimeInstance;
import com.devvault.shared.api.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;

import static com.devvault.runtime.application.WorkspaceGroupStartup.StepState.FAILED;
import static com.devvault.runtime.application.WorkspaceGroupStartup.StepState.READY;
import static com.devvault.runtime.application.WorkspaceGroupStartup.StepState.SKIPPED;
import static com.devvault.runtime.application.WorkspaceGroupStartup.StepState.STARTING;
import static com.devvault.runtime.application.WorkspaceGroupStartup.StepState.WAITING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkspaceGroupStartupTest {
    @Test
    void waitsForEachProjectAndSkipsTheRestAfterAReadinessFailure() {
        UUID workspace = UUID.randomUUID();
        UUID database = UUID.randomUUID();
        UUID api = UUID.randomUUID();
        UUID frontend = UUID.randomUUID();
        List<UUID> order = List.of(database, api, frontend);
        var projects = mock(ProjectLookupService.class);
        when(projects.findIdsByWorkspaceId(workspace)).thenReturn(order);
        var starter = mock(StartProjectUseCase.class);
        var tasks = new ArrayDeque<Runnable>();
        var startup = new WorkspaceGroupStartup(projects, starter, tasks::add);
        when(starter.execute(database)).thenAnswer(invocation -> {
            assertEquals(List.of(STARTING, WAITING, WAITING), statuses(startup.latest(workspace)));
            var ready = new RuntimeInstance(database);
            ready.markRunning();
            return ready;
        });
        when(starter.execute(api)).thenAnswer(invocation -> {
            assertEquals(List.of(READY, STARTING, WAITING), statuses(startup.latest(workspace)));
            var failed = new RuntimeInstance(api);
            failed.markFailed("No respondió al health check");
            return failed;
        });

        assertEquals(List.of(WAITING, WAITING, WAITING), statuses(startup.start(workspace, order)));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class,
                () -> startup.start(workspace, order)).getStatus());
        verifyNoInteractions(starter);
        tasks.remove().run();

        var report = startup.latest(workspace);
        assertEquals(WorkspaceGroupStartup.State.FAILED, report.status());
        assertEquals(List.of(READY, FAILED, SKIPPED), statuses(report));
        var calls = inOrder(starter);
        calls.verify(starter).execute(database);
        calls.verify(starter).execute(api);
        calls.verifyNoMoreInteractions();
    }

    private List<WorkspaceGroupStartup.StepState> statuses(WorkspaceGroupStartup.Report report) {
        return report.steps().stream().map(WorkspaceGroupStartup.Step::status).toList();
    }
}
