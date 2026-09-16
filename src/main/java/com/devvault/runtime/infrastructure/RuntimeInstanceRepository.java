package com.devvault.runtime.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import com.devvault.runtime.domain.RuntimeInstance;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository para RuntimeInstance.
 */
public interface RuntimeInstanceRepository extends JpaRepository<RuntimeInstance, UUID> {

    Optional<RuntimeInstance> findFirstByProjectIdOrderByStartedAtDesc(UUID projectId);
}
