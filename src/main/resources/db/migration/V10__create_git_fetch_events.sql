CREATE TABLE git_fetch_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    fetched_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL,
    remotes VARCHAR(500) NOT NULL
);

CREATE INDEX idx_git_fetch_events_project_fetched ON git_fetch_events(project_id, fetched_at DESC);
