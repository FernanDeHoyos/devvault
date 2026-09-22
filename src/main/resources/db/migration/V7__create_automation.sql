-- V7__create_automation.sql

CREATE TABLE automation_rules (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id  UUID REFERENCES projects(id) ON DELETE CASCADE, -- nullable: regla global si es NULL
    name        VARCHAR(120) NOT NULL,
    is_enabled  BOOLEAN NOT NULL DEFAULT true
);

CREATE INDEX idx_automation_rules_project_id ON automation_rules(project_id);
CREATE INDEX idx_automation_rules_enabled ON automation_rules(is_enabled) WHERE is_enabled = true;

CREATE TABLE triggers (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id     UUID NOT NULL REFERENCES automation_rules(id) ON DELETE CASCADE,
    event_type  VARCHAR(80) NOT NULL -- ej: "ProjectFailedEvent", "ProjectStartedEvent"
);

CREATE INDEX idx_triggers_rule_id ON triggers(rule_id);
CREATE INDEX idx_triggers_event_type ON triggers(event_type);

CREATE TABLE conditions (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id     UUID NOT NULL REFERENCES automation_rules(id) ON DELETE CASCADE,
    expression  TEXT NOT NULL -- expresión SpEL, ej: "reason.contains('puerto')"
);

CREATE INDEX idx_conditions_rule_id ON conditions(rule_id);

CREATE TABLE actions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    rule_id      UUID NOT NULL REFERENCES automation_rules(id) ON DELETE CASCADE,
    action_type  VARCHAR(50) NOT NULL, -- ej: "LOG"
    params       JSONB
);

CREATE INDEX idx_actions_rule_id ON actions(rule_id);