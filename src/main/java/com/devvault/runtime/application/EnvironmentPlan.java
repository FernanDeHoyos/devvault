package com.devvault.runtime.application;

import java.util.*;

/** Explicit dependencies only: detected architecture is not an execution dependency. */
public final class EnvironmentPlan {
    private EnvironmentPlan() {}
    public record Entry(UUID projectId, List<UUID> dependsOn) {}
    public static List<List<UUID>> layers(List<Entry> entries) {
        if (entries == null || entries.isEmpty() || entries.size() > 25) throw new IllegalArgumentException("Selecciona entre 1 y 25 proyectos.");
        Map<UUID, Set<UUID>> pending = new LinkedHashMap<>();
        for (Entry entry : entries) {
            if (entry == null || entry.projectId() == null || entry.dependsOn() == null || entry.dependsOn().stream().anyMatch(Objects::isNull)
                    || pending.put(entry.projectId(), new HashSet<>(entry.dependsOn())) != null) {
                throw new IllegalArgumentException("Los proyectos y dependencias deben ser válidos y únicos.");
            }
        }
        for (var dependencies : pending.values()) {
            if (!pending.keySet().containsAll(dependencies)) throw new IllegalArgumentException("Una dependencia no pertenece al entorno.");
        }
        List<List<UUID>> result = new ArrayList<>();
        Set<UUID> ready = new HashSet<>();
        while (!pending.isEmpty()) {
            List<UUID> layer = pending.entrySet().stream().filter(e -> ready.containsAll(e.getValue())).map(Map.Entry::getKey).toList();
            if (layer.isEmpty()) throw new IllegalArgumentException("Las dependencias forman un ciclo.");
            result.add(layer);
            ready.addAll(layer);
            layer.forEach(pending::remove);
        }
        return result;
    }
}
