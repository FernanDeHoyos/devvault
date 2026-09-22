package com.devvault.automation.infrastructure;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.devvault.automation.domain.Condition;

public interface ConditionRepository extends JpaRepository<Condition, UUID> {
    List<Condition> findByRuleId(UUID ruleId);

    void deleteByRuleId(UUID ruleId);
}
