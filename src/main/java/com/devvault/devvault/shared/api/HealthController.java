package com.devvault.devvault.shared.api;

import java.time.Instant;
import javax.sql.DataSource;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public Map<String, Object> healthCheck() {
        boolean dbReady = isDataBasseReady();
        return Map.of(
                "status", dbReady ? "UP" : "DOWN",
                "database", dbReady ? "Operative" : "Unreachable",
                "timestamp", Instant.now().toString());
    }

    private boolean isDataBasseReady() {
        try (var conn = dataSource.getConnection()) {
            return conn.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }
}