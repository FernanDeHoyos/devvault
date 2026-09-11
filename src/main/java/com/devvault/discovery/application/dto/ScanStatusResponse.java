package com.devvault.discovery.application.dto;

import com.devvault.discovery.application.ScanStatusTracker;

public record ScanStatusResponse(
    String status,
    int projectsFound,
    java.time.Instant startedAt,
    java.time.Instant finishedAt,
    String errorMessage
) {
    public static ScanStatusResponse fromSnapshot(ScanStatusTracker.Snapshot snapshot) {
        return new ScanStatusResponse(
            snapshot.status().name(),
            snapshot.projectsFound(),
            snapshot.startedAt(),
            snapshot.finishedAt(),
            snapshot.errorMessage()
        );
    }
    
}
