package com.devvault.discovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.devvault.discovery.plugin.SourceLanguagesPlugin;

class SourceLanguagesPluginTest {

    private final SourceLanguagesPlugin plugin = new SourceLanguagesPlugin();

    @Test
    void detectsSourceLanguagesWithEvidence(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("index.html"), "<main></main>");
        Files.writeString(tempDir.resolve("schema.sql"), "create table sample(id integer);");

        var result = plugin.detect(tempDir).orElseThrow();

        assertEquals("HTML", result.language());
        assertEquals(2, ((java.util.List<?>) result.rawMarkers().get("stackFindings")).size());
        assertTrue(((java.util.List<?>) result.rawMarkers().get("stackFindings")).stream()
                .anyMatch(finding -> finding.toString().contains("DATABASE")));
    }
}
