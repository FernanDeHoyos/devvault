package com.devvault.runtime.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

@Component
public class LocalProcessManager {

    private static final long DESCENDANT_SCAN_INTERVAL_MS = 100;
    private static final long GRACEFUL_STOP_TIMEOUT_MS = 750;
    private static final long FORCE_STOP_TIMEOUT_MS = 2_000;

    private final Map<UUID, ManagedProcess> processes = new ConcurrentHashMap<>();

    public void register(UUID projectId, Process process) {
        ManagedProcess managed = new ManagedProcess(process);
        ManagedProcess previous = processes.put(projectId, managed);
        if (previous != null) {
            previous.stopTracking();
        }
        managed.startTracking(projectId);
    }

    /** Obtiene el proceso raíz registrado para un proyecto. */
    public Process get(UUID projectId) {
        ManagedProcess managed = processes.get(projectId);
        return managed == null ? null : managed.process;
    }

    /** Indica si el proceso raíz o cualquiera de sus descendientes observados sigue vivo. */
    public boolean isAlive(UUID projectId) {
        ManagedProcess managed = processes.get(projectId);
        return managed != null && managed.isAlive();
    }

    /** Elimina el proceso registrado y detiene su observador. */
    public void remove(UUID projectId) {
        ManagedProcess managed = processes.remove(projectId);
        if (managed != null) {
            managed.stopTracking();
        }
    }

    /** Detiene el proceso registrado o lo recupera por su PID persistido. */
    public boolean stop(UUID projectId) {
        return stop(projectId, null, null);
    }

    /**
     * Detiene el árbol observado incluso si el proceso raíz ya terminó y dejó
     * procesos descendientes vivos. El PID persistido se usa tras reiniciar
     * DevVault, cuando ya no queda un proceso registrado en memoria.
     */
    public boolean stop(UUID projectId, Integer fallbackPid, Instant expectedStartedAt) {
        ManagedProcess managed = processes.get(projectId);
        if (managed != null) {
            managed.captureDescendants();
            managed.stopTracking();
            boolean stopped = stopHandle(managed.root, managed.descendants());
            if (stopped) {
                processes.remove(projectId, managed);
            }
            return stopped;
        }
        if (fallbackPid == null || fallbackPid <= 0) {
            return false;
        }

        Optional<ProcessHandle> persistedProcess = ProcessHandle.of(fallbackPid);
        if (persistedProcess.isEmpty() || !persistedProcess.get().isAlive()) {
            return false;
        }

        // Evita matar un proceso ajeno si el SO reutilizó el PID persistido.
        if (expectedStartedAt != null) {
            Optional<Instant> processStartedAt = persistedProcess.get().info().startInstant();
            if (processStartedAt.isPresent()
                    && processStartedAt.get().isAfter(expectedStartedAt.plus(Duration.ofMinutes(5)))) {
                return false;
            }
        }
        return stopHandle(new TrackedProcess(persistedProcess.get()), List.of());
    }

    private boolean stopHandle(TrackedProcess root, List<TrackedProcess> observedDescendants) {
        List<TrackedProcess> descendants = collectDescendants(root, observedDescendants);
        if (isWindows()) {
            stopWindowsTree(root, descendants);
        } else {
            descendants.stream().filter(TrackedProcess::isAlive).forEach(process -> process.handle.destroy());
            if (root.isAlive()) {
                root.handle.destroy();
            }
            waitForExit(root, descendants, GRACEFUL_STOP_TIMEOUT_MS);
            descendants.stream().filter(TrackedProcess::isAlive).forEach(process -> process.handle.destroyForcibly());
            if (root.isAlive()) {
                root.handle.destroyForcibly();
            }
        }
        waitForExit(root, descendants, FORCE_STOP_TIMEOUT_MS);
        return !root.isAlive() && descendants.stream().noneMatch(TrackedProcess::isAlive);
    }

    private List<TrackedProcess> collectDescendants(TrackedProcess root, List<TrackedProcess> observed) {
        Map<Long, TrackedProcess> byPid = new LinkedHashMap<>();
        observed.forEach(process -> byPid.put(process.handle.pid(), process));
        if (root.isAlive()) {
            root.handle.descendants().forEach(handle -> byPid.put(handle.pid(), new TrackedProcess(handle)));
        }
        byPid.remove(root.handle.pid());
        return new ArrayList<>(byPid.values());
    }

    private void stopWindowsTree(TrackedProcess root, List<TrackedProcess> descendants) {
        Process killer = null;
        if (root.isAlive()) {
            try {
                killer = new ProcessBuilder(
                        "taskkill.exe", "/PID", Long.toString(root.handle.pid()), "/T", "/F")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (!killer.waitFor(5, TimeUnit.SECONDS)) {
                    killer.destroyForcibly();
                }
            } catch (java.io.IOException ignored) {
                // ProcessHandle fallback below covers restricted Windows hosts.
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (killer != null) {
                    killer.destroyForcibly();
                }
            }
        }

        // taskkill cannot reach children that detached from the root after they
        // were observed, so terminate the saved handles as a second pass.
        descendants.stream().filter(TrackedProcess::isAlive)
                .forEach(process -> process.handle.destroyForcibly());
        if (root.isAlive()) {
            root.handle.destroyForcibly();
        }
    }

    private void waitForExit(TrackedProcess root, List<TrackedProcess> descendants, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline
                && (root.isAlive() || descendants.stream().anyMatch(TrackedProcess::isAlive))) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static final class ManagedProcess {
        private final Process process;
        private final TrackedProcess root;
        private final Map<Long, TrackedProcess> observedDescendants = new ConcurrentHashMap<>();
        private final AtomicBoolean tracking = new AtomicBoolean(true);
        private volatile Thread tracker;

        private ManagedProcess(Process process) {
            this.process = process;
            this.root = new TrackedProcess(process.toHandle());
        }

        private void startTracking(UUID projectId) {
            tracker = new Thread(() -> {
                while (tracking.get() && process.isAlive()) {
                    captureDescendants();
                    try {
                        Thread.sleep(DESCENDANT_SCAN_INTERVAL_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                captureDescendants();
            }, "devvault-process-tree-" + projectId);
            tracker.setDaemon(true);
            tracker.start();
        }

        private void captureDescendants() {
            if (!process.isAlive()) {
                return;
            }
            process.toHandle().descendants().forEach(handle -> {
                TrackedProcess observed = new TrackedProcess(handle);
                if (observed.isAlive()) {
                    observedDescendants.putIfAbsent(handle.pid(), observed);
                }
            });
            observedDescendants.entrySet().removeIf(entry -> !entry.getValue().isAlive());
        }

        private List<TrackedProcess> descendants() {
            return List.copyOf(observedDescendants.values());
        }

        private boolean isAlive() {
            return process.isAlive() || observedDescendants.values().stream().anyMatch(TrackedProcess::isAlive);
        }

        private void stopTracking() {
            tracking.set(false);
            Thread currentTracker = tracker;
            if (currentTracker != null) {
                currentTracker.interrupt();
            }
        }
    }

    /** PID plus its creation time, to avoid killing an unrelated process after PID reuse. */
    private static final class TrackedProcess {
        private final ProcessHandle handle;
        private final Optional<Instant> startedAt;

        private TrackedProcess(ProcessHandle handle) {
            this.handle = handle;
            this.startedAt = handle.info().startInstant();
        }

        private boolean isAlive() {
            Optional<ProcessHandle> current = ProcessHandle.of(handle.pid());
            if (current.isEmpty() || !current.get().isAlive()) {
                return false;
            }
            Optional<Instant> currentStartedAt = current.get().info().startInstant();
            return startedAt.isEmpty() || currentStartedAt.isEmpty() || startedAt.equals(currentStartedAt);
        }
    }
}
