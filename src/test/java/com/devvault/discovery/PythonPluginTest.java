package com.devvault.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.devvault.discovery.plugin.PythonPlugin;

class PythonPluginTest {

    private final PythonPlugin plugin = new PythonPlugin();

    @Test
    void detectsPythonAndDjangoFromRequirements(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("requirements.txt"), "Django==5.0.2\nrequests>=2.31\n");

        var result = plugin.detect(tempDir).orElseThrow();

        assertEquals("Python", result.language());
        assertEquals("Django", result.framework());
        assertEquals("5.0.2", result.version());
        assertEquals(3, result.rawMarkers().get("stackFindings") instanceof java.util.List<?> list ? list.size() : 0);
    }

    @Test
    void doesNotDetectAnEmptyDirectory(@TempDir Path tempDir) {
        assertTrue(plugin.detect(tempDir).isEmpty());
    }
}
