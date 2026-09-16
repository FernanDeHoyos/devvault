-- V3__create_runtime.sql

CREATE TABLE runtime_instances (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id      UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    started_at      TIMESTAMPTZ,
    stopped_at      TIMESTAMPTZ,
    overall_status  VARCHAR(20) NOT NULL,
    failure_reason  TEXT
);

CREATE INDEX idx_runtime_instances_project_id ON runtime_instances(project_id);

CREATE TABLE services (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id   UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name         VARCHAR(80) NOT NULL,
    type         VARCHAR(30) NOT NULL,
    port         INT,
    status       VARCHAR(20) NOT NULL
);

CREATE INDEX idx_services_project_id ON services(project_id);
CREATE UNIQUE INDEX idx_services_project_name ON services(project_id, name);

CREATE TABLE containers (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    service_id            UUID NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    docker_container_id   VARCHAR(64),
    state                 VARCHAR(20) NOT NULL
);

CREATE INDEX idx_containers_service_id ON containers(service_id);