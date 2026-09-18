package com.devvault.runtime.application;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class LocalProcessManager {

    private final Map<UUID, Process> processes = new ConcurrentHashMap<>();

    public void register(UUID projectId, Process process) {
        processes.put(projectId, process);
    }

    public Process get(UUID projectId) {
        return processes.get(projectId);
    }

    public void remove(UUID projectId) {
        processes.remove(projectId);
    }

   public boolean stop(UUID projectId) {

    Process process = processes.remove(projectId);

    if (process == null) {
        return false;
    }

    ProcessHandle handle = process.toHandle();

    // Detener hijos primero
    handle.descendants()
            .filter(ProcessHandle::isAlive)
            .forEach(ProcessHandle::destroy);

    // Detener proceso principal
    if (handle.isAlive()) {
        handle.destroy();
    }

    // Dar tiempo para terminar correctamente
    try {
        Thread.sleep(500);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
    }

    // Forzar únicamente los que sobrevivieron
    handle.descendants()
            .filter(ProcessHandle::isAlive)
            .forEach(ProcessHandle::destroyForcibly);

    if (handle.isAlive()) {
        handle.destroyForcibly();
    }

    return true;
}
}
