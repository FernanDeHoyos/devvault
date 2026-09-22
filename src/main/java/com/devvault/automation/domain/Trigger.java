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
@Table(name = "triggers")
@Getter 
@NoArgsConstructor 
public class Trigger {
    
    @Id 
    @GeneratedValue
    private UUID id;
 
    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;
 
    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType; // simple name de la clase del evento, ej: "ProjectFailedEvent"
 
    public Trigger(UUID ruleId, String eventType) {
        this.ruleId = ruleId;
        this.eventType = eventType;
    }
}
