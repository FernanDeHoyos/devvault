package com.devvault.runtime.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import com.devvault.runtime.domain.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository para Service.
 */
public interface ServiceRepository extends JpaRepository<Service, UUID> {

    List<Service> findByProjectId(UUID projectId);

    Optional<Service> findByProjectIdAndName(UUID projectId, String name);

}
