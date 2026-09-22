package com.devvault.automation.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "actions")
@Getter
@NoArgsConstructor
public class Action {

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType; // ej: "LOG"

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> params;

    public Action(UUID ruleId, String actionType, Map<String, Object> params) {
        this.ruleId = ruleId;
        this.actionType = actionType;
        this.params = params;
    }
}