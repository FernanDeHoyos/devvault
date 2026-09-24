package com.devvault.discovery.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "git_fetch_events")
@Getter
@NoArgsConstructor
public class GitFetchEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "project_id", nullable = false)
    private UUID projectId;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(nullable = false, length = 500)
    private String remotes;

    public GitFetchEvent(UUID projectId, String status, String remotes) {
        this.projectId = projectId;
        this.fetchedAt = Instant.now();
        this.status = status;
        this.remotes = remotes;
    }
}
