package com.devvault.runtime.application;

import com.devvault.discovery.application.ProjectLookupService;
import com.devvault.runtime.infrastructure.DockerComposeRunner;
import com.devvault.runtime.infrastructure.LocalPortProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StartupDiagnosticsTest {
    @Test
    void reportsMissingCommandRequiredVariableAndPortConflictWithoutReturningValues(@TempDir Path root)
            throws IOException {
        Files.writeString(root.resolve("devvault.run.json"), """
                {"version":1,"service":{"name":"app","command":["./missing-executable"],"port":8181,
                "requiredEnvironment":["DATABASE_URL","API_TOKEN"],
                "environment":{"DATABASE_URL":"","API_TOKEN":"private-fixture-value"}}}
                """);
        var id = UUID.randomUUID();
        var projects = mock(ProjectLookupService.class);
        when(projects.findById(id)).thenReturn(Optional.of(
                new ProjectLookupService.ProjectSummary(id, "app", root.toString())));
        var ports = mock(LocalPortProbe.class);
        when(ports.scanListeners(Set.of(8181))).thenReturn(new LocalPortProbe.ListenerScan(true,
                Map.of(8181, List.of(new LocalPortProbe.PortListener(8181, 1L, "app")))));
        var resolver = new LocalRunConfigurationResolver(new ProjectRunRecipeLoader(), List.of());
        var diagnostics = new StartupDiagnostics(projects, resolver, new DockerComposeRunner(), ports);

        var report = diagnostics.inspect(id);

        assertEquals("LOCAL", report.mode());
        assertEquals(3, report.checks().size());
        assertTrue(report.checks().stream().anyMatch(check -> check.message().contains("ejecutable")));
        assertTrue(report.checks().stream().anyMatch(check -> check.message().contains("DATABASE_URL")));
        assertTrue(report.checks().stream().anyMatch(check ->
                check.level().equals("WARNING") && check.message().contains("8181")));
        assertFalse(report.toString().contains("API_TOKEN"));
        assertFalse(report.toString().contains("private-fixture-value"));
    }
}
