-- V1__create_workspace.sql
-- Tablas base: users y workspaces

CREATE EXTENSION IF NOT EXISTS "pgcrypto";  -- necesaria para gen_random_uuid()

CREATE TABLE users (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email          VARCHAR(255) UNIQUE NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    role           VARCHAR(20)  NOT NULL DEFAULT 'USER',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Usuario local por defecto: mientras auth no está activa (HU-01),
-- todo Workspace se asocia a este usuario placeholder.
INSERT INTO users (id, email, password_hash, role)
VALUES ('00000000-0000-0000-0000-000000000001', 'local@devvault.dev', 'unused', 'ADMIN');

CREATE TABLE workspaces (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        VARCHAR(120) NOT NULL,
    path        TEXT NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Regla de negocio: no se puede registrar la misma ruta local dos veces (HU-02)
CREATE UNIQUE INDEX idx_workspaces_path ON workspaces(path);
CREATE INDEX idx_workspaces_user_id ON workspaces(user_id);