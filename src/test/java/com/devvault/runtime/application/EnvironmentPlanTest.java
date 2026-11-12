package com.devvault.runtime.application;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class EnvironmentPlanTest {
    @Test void independentProjectsShareALayerAndDependentsWait() {
        UUID db = UUID.randomUUID(), cache = UUID.randomUUID(), api = UUID.randomUUID(), ui = UUID.randomUUID();
        var layers = EnvironmentPlan.layers(List.of(new EnvironmentPlan.Entry(ui, List.of(api)),
                new EnvironmentPlan.Entry(db, List.of()), new EnvironmentPlan.Entry(cache, List.of()),
                new EnvironmentPlan.Entry(api, List.of(db, cache))));
        assertEquals(Set.of(db, cache), Set.copyOf(layers.get(0)));
        assertEquals(List.of(api), layers.get(1));
        assertEquals(List.of(ui), layers.get(2));
    }
    @Test void rejectsCyclesDuplicatesAndMissingDependencies() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> EnvironmentPlan.layers(List.of(new EnvironmentPlan.Entry(a, List.of(b)), new EnvironmentPlan.Entry(b, List.of(a)))));
        assertThrows(IllegalArgumentException.class, () -> EnvironmentPlan.layers(List.of(new EnvironmentPlan.Entry(a, List.of(b)))));
        assertThrows(IllegalArgumentException.class, () -> EnvironmentPlan.layers(List.of(new EnvironmentPlan.Entry(a, List.of()), new EnvironmentPlan.Entry(a, List.of()))));
    }
}
