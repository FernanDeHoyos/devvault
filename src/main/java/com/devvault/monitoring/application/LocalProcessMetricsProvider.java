package com.devvault.monitoring.application;

import oshi.SystemInfo;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/** Samples the root local process and its OS-reported descendants. */
@Component
public class LocalProcessMetricsProvider {
    private static final Logger log = LoggerFactory.getLogger(LocalProcessMetricsProvider.class);
    private static final long CPU_SAMPLE_INTERVAL_MS = 1000;

    private final OperatingSystem operatingSystem = new SystemInfo().getOperatingSystem();

    public record Sample(Double cpuPercent, double privateMemoryMb) { }

    public Sample measure(int rootPid) {
        if (!isAlive(rootPid)) return null;

        try {
            Map<Integer, OSProcess> previous = snapshotTree(rootPid);
            if (previous.isEmpty()) return null;

            Thread.sleep(CPU_SAMPLE_INTERVAL_MS);

            Map<Integer, OSProcess> current = snapshotTree(rootPid);
            if (current.isEmpty()) return null;

            double cpuLoad = 0;
            boolean hasCpuDelta = false;
            long privateMemoryBytes = 0;
            for (OSProcess process : current.values()) {
                privateMemoryBytes += Math.max(0, process.getPrivateResidentMemory());
                OSProcess prior = previous.get(process.getProcessID());
                if (prior == null || prior.getStartTime() != process.getStartTime()) continue;

                double processLoad = process.getProcessCpuLoadBetweenTicks(prior);
                if (Double.isFinite(processLoad) && processLoad >= 0) {
                    cpuLoad += processLoad;
                    hasCpuDelta = true;
                }
            }

            Double cpuPercent = hasCpuDelta ? cpuLoad * 100.0 : null;
            double memoryMb = privateMemoryBytes / (1024.0 * 1024.0);
            return new Sample(cpuPercent, memoryMb);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Muestra local interrumpida para el PID {}", rootPid);
            return null;
        } catch (RuntimeException e) {
            log.debug("No se pudieron leer métricas OSHI para el PID {}", rootPid, e);
            return null;
        }
    }

    private Map<Integer, OSProcess> snapshotTree(int rootPid) {
        Map<Integer, OSProcess> processes = new HashMap<>();
        OSProcess root = operatingSystem.getProcess(rootPid);
        if (root != null && isUsable(root)) {
            processes.put(root.getProcessID(), root);
        }

        operatingSystem.getDescendantProcesses(rootPid, null, null, 0).stream()
                .filter(LocalProcessMetricsProvider::isUsable)
                .forEach(process -> processes.put(process.getProcessID(), process));
        return processes;
    }

    private static boolean isUsable(OSProcess process) {
        return process.getState() != OSProcess.State.INVALID;
    }

    private boolean isAlive(int pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }
}
