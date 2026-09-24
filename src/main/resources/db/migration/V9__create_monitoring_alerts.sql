CREATE TABLE alerts (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    type VARCHAR(80) NOT NULL,
    message TEXT NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    resolved_at TIMESTAMPTZ,
    CONSTRAINT chk_alerts_status CHECK (status IN ('ACTIVE', 'RESOLVED'))
);
CREATE INDEX idx_alerts_project_created ON alerts(project_id, created_at DESC);
CREATE INDEX idx_alerts_status_created ON alerts(status, created_at DESC);
