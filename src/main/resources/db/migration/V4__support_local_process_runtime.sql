-- V4__support_local_process_runtime.sql
-- Extiende containers para representar tanto contenedores Docker como
-- procesos locales (ej. `npm run dev`), diferenciados por 'kind'.

ALTER TABLE containers
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'DOCKER',
    ADD COLUMN pid INTEGER,
    ADD COLUMN command TEXT;

ALTER TABLE containers
    ADD CONSTRAINT chk_containers_kind CHECK (kind IN ('DOCKER', 'LOCAL_PROCESS'));

-- docker_container_id ya era nullable en V3 — un LOCAL_PROCESS simplemente
-- no lo usa (queda NULL), y pid/command quedan NULL para DOCKER.