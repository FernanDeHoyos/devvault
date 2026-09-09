-- V2__create_project_discovery.sql

CREATE TABLE projects (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    workspace_id  UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    name          VARCHAR(120) NOT NULL,
    path          TEXT NOT NULL,
    language      VARCHAR(50),
    framework     VARCHAR(50),
    version       VARCHAR(30),
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
);

-- Regla de negocio: al re-escanear, un mismo path dentro de un workspace se actualiza, no se duplica
CREATE UNIQUE INDEX idx_projects_workspace_path ON projects(workspace_id, path);
CREATE INDEX idx_projects_workspace_id ON projects(workspace_id);
CREATE INDEX idx_projects_status ON projects(status);

CREATE TABLE project_profiles (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id   UUID NOT NULL UNIQUE REFERENCES projects(id) ON DELETE CASCADE,
    detected_at  TIMESTAMPTZ NOT NULL,
    raw_markers  JSONB
);

CREATE INDEX idx_project_profiles_raw_markers ON project_profiles USING GIN (raw_markers);