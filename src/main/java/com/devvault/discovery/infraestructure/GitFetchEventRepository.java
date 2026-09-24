package com.devvault.discovery.infraestructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.devvault.discovery.domain.GitFetchEvent;

public interface GitFetchEventRepository extends JpaRepository<GitFetchEvent, UUID> {
    List<GitFetchEvent> findTop10ByProjectIdOrderByFetchedAtDesc(UUID projectId);
    Optional<GitFetchEvent> findFirstByProjectIdAndStatusOrderByFetchedAtDesc(UUID projectId, String status);
    void deleteByProjectId(UUID projectId);
}
