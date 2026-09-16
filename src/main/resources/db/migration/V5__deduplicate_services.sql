-- V5__deduplicate_services.sql
-- Corrige duplicados en services(project_id, name) causados por una
-- condición de carrera en executeLocal (dos peticiones de start
-- concurrentes insertando antes de que existiera una restricción única
-- real). Se conserva la fila más reciente (mayor id) de cada duplicado;
-- los containers de las filas eliminadas se van en cascada (ON DELETE
-- CASCADE ya definido en V3).

DELETE FROM services a
USING services b
WHERE a.id < b.id
  AND a.project_id = b.project_id
  AND a.name = b.name;

-- Reafirma la restricción (idempotente: si ya existía, no falla)
CREATE UNIQUE INDEX IF NOT EXISTS idx_services_project_name ON services(project_id, name);