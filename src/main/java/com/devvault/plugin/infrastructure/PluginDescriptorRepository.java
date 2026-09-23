package com.devvault.plugin.infrastructure;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.devvault.plugin.domain.PluginDescriptor;

public interface PluginDescriptorRepository extends JpaRepository<PluginDescriptor, UUID>{

    Optional<PluginDescriptor> findByName(String name);

    List<PluginDescriptor> findByEnabledTrue();
}
