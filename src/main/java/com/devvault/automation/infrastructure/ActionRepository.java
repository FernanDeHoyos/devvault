package com.devvault.automation.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.devvault.automation.domain.Action;

public interface ActionRepository extends JpaRepository<Action, UUID>{
     List<Action> findByRuleId(UUID ruleId);

    void deleteByRuleId(UUID ruleId);
}
