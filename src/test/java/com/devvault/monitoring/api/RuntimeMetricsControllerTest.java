package com.devvault.monitoring.api;

import com.devvault.monitoring.application.MonitoringService;
import com.devvault.monitoring.application.dto.ContainerMetricsResponse;
import com.devvault.runtime.domain.*;
import com.devvault.runtime.infrastructure.RuntimeInstanceRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RuntimeMetricsControllerTest {
    @Test void oneFailedProjectDoesNotHideHealthyProjects() {
        var monitoring = mock(MonitoringService.class);
        var repository = mock(RuntimeInstanceRepository.class);
        UUID good = UUID.randomUUID(), bad = UUID.randomUUID();
        when(repository.findByOverallStatus(RuntimeStatus.RUNNING)).thenReturn(List.of(new RuntimeInstance(good), new RuntimeInstance(bad)));
        when(monitoring.metrics(good, null, null)).thenReturn(List.of(new ContainerMetricsResponse(UUID.randomUUID(), "api", "LOCAL_PROCESS", null, 123, 1.0, 50.0, Instant.now())));
        when(monitoring.metrics(bad, null, null)).thenThrow(new IllegalStateException("private diagnostic"));
        var response = new RuntimeMetricsController(monitoring, repository).metrics();
        assertEquals(1, response.get(0).metrics().size());
        assertNull(response.get(0).error());
        assertTrue(response.get(1).metrics().isEmpty());
        assertNotNull(response.get(1).error());
        assertFalse(response.get(1).error().contains("private diagnostic"));
    }
}
