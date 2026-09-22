package com.devvault.automation.infrastructure;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import com.devvault.automation.domain.AutomationRule;

public interface AutomationRuleRepository extends JpaRepository<AutomationRule, UUID>{
    
}
