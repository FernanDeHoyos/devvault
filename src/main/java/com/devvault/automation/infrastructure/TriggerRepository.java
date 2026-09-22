package com.devvault.automation.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.devvault.automation.domain.Trigger;

public interface TriggerRepository extends JpaRepository<Trigger, UUID>{
    List<Trigger> findByEventType(String eventType);

    List<Trigger> findByRuleId(UUID ruleId);

    void deleteByRuleId(UUID ruleId);
}
