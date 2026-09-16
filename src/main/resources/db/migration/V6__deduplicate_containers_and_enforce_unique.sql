-- V6__deduplicate_containers_and_enforce_unique.sql
-- Igual que V5 pero para containers: el índice de V3 era de búsqueda
-- (no único), lo que permitió duplicados reales de Container por Service
-- al no existir find-or-update en executeLocal. Se corrige el código en
-- StartProjectUseCase y aquí se limpia el dato existente + se refuerza
-- la relación 1:1 Service-Container definida en el ERD original.

DELETE FROM containers a
USING containers b
WHERE a.id < b.id
  AND a.service_id = b.service_id;

DROP INDEX IF EXISTS idx_containers_service_id;
CREATE UNIQUE INDEX idx_containers_service_id ON containers(service_id);