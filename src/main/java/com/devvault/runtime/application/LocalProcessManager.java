package com.devvault.runtime.application;

import java.util.Map;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class LocalProcessManager {

    private final Map<UUID, Process> processes = new ConcurrentHashMap<>();

    public void register(UUID projectId, Process process) {
        processes.put(projectId, process);
    }

    /**
     * Obtiene el proceso por ID.
     * @param projectId ID del proyecto
     * @return Proceso
     */
    public Process get(UUID projectId) {
        return processes.get(projectId);
    }

    /**
     * Elimina el proceso por ID.
     * @param projectId ID del proyecto
     */
    public void remove(UUID projectId) {
        processes.remove(projectId);
    }

    /**
     * CU-05 Detiene el proceso.
     * @param projectId ID del proyecto
     * @return True si se detuvo el proceso, false si no existía
     */
   public boolean stop(UUID projectId) {
    return stop(projectId, null, null);
   }

   /** Stops a registered process, or recovers it by its persisted PID after an app restart. */
   public boolean stop(UUID projectId, Integer fallbackPid, Instant expectedStartedAt) {
    Process process = processes.remove(projectId);
    if (process != null) {
        return stopHandle(process.toHandle());
    }
    if (fallbackPid == null || fallbackPid <= 0) {
        return false;
    }

    Optional<ProcessHandle> persistedProcess = ProcessHandle.of(fallbackPid);
    if (persistedProcess.isEmpty() || !persistedProcess.get().isAlive()) {
        return false;
    }

    // Do not kill an unrelated process if the operating system has reused this PID.
    if (expectedStartedAt != null) {
        Optional<Instant> processStartedAt = persistedProcess.get().info().startInstant();
        if (processStartedAt.isPresent()
                && processStartedAt.get().isAfter(expectedStartedAt.plus(Duration.ofMinutes(5)))) {
            return false;
        }
    }
    return stopHandle(persistedProcess.get());
   }

   private boolean stopHandle(ProcessHandle handle) {
    if (!handle.isAlive()) {
        return true;
    }
    List<ProcessHandle> descendants = handle.descendants().toList();
    descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy);
    handle.destroy();

    try {
        handle.onExit().get(750, java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    } catch (Exception ignored) {
        // Force-stop the process tree below if graceful termination timed out.
    }

    descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    if (handle.isAlive()) {
        handle.destroyForcibly();
    }
    try {
        handle.onExit().get(750, java.util.concurrent.TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    } catch (Exception ignored) {
        // The caller verifies whether the process is still alive.
    }
    return !handle.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive);
}
}
