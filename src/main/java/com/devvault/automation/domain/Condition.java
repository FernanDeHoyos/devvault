package com.devvault.automation.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity 
@Table (name = "conditions")
@Getter 
@NoArgsConstructor 
public class Condition {
    
    @Id 
    @GeneratedValue
    private UUID id;
 
    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;
 
    /** Expresión SpEL evaluada contra el contexto del evento, ej: "reason.contains('puerto')". */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String expression;
 
    public Condition(UUID ruleId, String expression) {
        this.ruleId = ruleId;
        this.expression = expression;
    }
}
