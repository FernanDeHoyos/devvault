CREATE TABLE saved_environments (
    id UUID PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    name VARCHAR(120) NOT NULL,
    definition TEXT NOT NULL
);
CREATE INDEX idx_environments_workspace ON saved_environments(workspace_id);
CREATE TABLE environment_runs (
    id UUID PRIMARY KEY,
    environment_id UUID NOT NULL REFERENCES saved_environments(id) ON DELETE CASCADE,
    status VARCHAR(20) NOT NULL,
    report TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_environment_runs_history ON environment_runs(environment_id, created_at DESC);
