package com.devvault.runtime.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.devvault.runtime.domain.Container;

/**
 * Repository para Container.
 */
public interface ContainerRepository extends JpaRepository<Container, UUID> {
    Optional<Container> findByServiceId(UUID serviceId);
}
