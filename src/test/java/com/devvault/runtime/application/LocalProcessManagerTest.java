package com.devvault.runtime.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class LocalProcessManagerTest {

    @Test
    void stopTerminatesTheRegisteredProcessTree() throws Exception {
        Process process = new ProcessBuilder(longRunningProcessCommand())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        UUID projectId = UUID.randomUUID();
        LocalProcessManager manager = new LocalProcessManager();
        manager.register(projectId, process);
        List<ProcessHandle> descendants = waitForDescendants(process);

        try {
            assertFalse(descendants.isEmpty(), "The fixture should have at least one child process");
            assertTrue(manager.stop(projectId), "The process tree should be terminated successfully");
            assertTrue(process.waitFor(2, TimeUnit.SECONDS), "The registered root process should exit");
            assertFalse(process.isAlive());
            assertTrue(descendants.stream().noneMatch(ProcessHandle::isAlive),
                    "Every captured child process should exit");
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        }
    }

    private List<String> longRunningProcessCommand() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return List.of("cmd.exe", "/c", "ping 127.0.0.1 -n 60 > NUL");
        }
        return List.of("sh", "-c", "sleep 60 & wait");
    }

    private List<ProcessHandle> waitForDescendants(Process process) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        List<ProcessHandle> descendants;
        do {
            descendants = process.toHandle().descendants().toList();
            if (!descendants.isEmpty()) {
                return descendants;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline && process.isAlive());
        return descendants;
    }
}
