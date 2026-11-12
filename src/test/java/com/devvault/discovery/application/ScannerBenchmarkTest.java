package com.devvault.discovery.application;

import com.devvault.discovery.plugin.*;
import com.devvault.plugin.domain.PluginDescriptor;
import com.devvault.plugin.infrastructure.PluginDescriptorRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ScannerBenchmarkTest {
    @Test void repeatedScanMeasuresRepresentativeWorkspace(@TempDir Path root) throws Exception {
        for (int project = 0; project < 20; project++) {
            Path directory = Files.createDirectories(root.resolve("project-" + project));
            Files.writeString(directory.resolve("package.json"), "{\"name\":\"sample\",\"scripts\":{\"start\":\"node server.js\"}}");
            Path source = Files.createDirectories(directory.resolve("src"));
            for (int file = 0; file < 50; file++) Files.writeString(source.resolve("module" + file + ".js"), "export const value = 1;");
            Files.createDirectories(directory.resolve("node_modules/ignored"));
            Files.writeString(directory.resolve("node_modules/ignored/package.json"), "{}");
        }
        var repository = mock(PluginDescriptorRepository.class);
        when(repository.findByEnabledTrue()).thenReturn(List.of(
                new PluginDescriptor("react-node-detector", "package.json", "1.0"),
                new PluginDescriptor("project-architecture-detector", "package.json", "1.0")));
        var engine = new ScannerEngine(new TechnologyPluginCatalog(List.of(new ReactNodePlugin(), new ProjectArchitecturePlugin()), new ObjectMapper(), root.resolve("extensions")), repository);
        for (int iteration = 0; iteration < 3; iteration++) {
            long started = System.nanoTime();
            assertEquals(20, engine.scan(root).size());
            System.out.printf("SCAN_BENCHMARK projects=20 files=1000 iteration=%d elapsedMs=%d%n", iteration, (System.nanoTime() - started) / 1_000_000);
        }
    }
}
