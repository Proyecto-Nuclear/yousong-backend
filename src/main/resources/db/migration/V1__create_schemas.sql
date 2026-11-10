-- Proyecto Nuclear VII: logical separation of the database (nuclear_vii).
-- Schemas separate responsibilities; privileges (roles) are granted in a later migration.
CREATE SCHEMA IF NOT EXISTS operacion;
CREATE SCHEMA IF NOT EXISTS integracion;
CREATE SCHEMA IF NOT EXISTS analitica;

COMMENT ON SCHEMA operacion IS 'Session, participation, playback, staff and privacy (20 tables)';
COMMENT ON SCHEMA integracion IS 'Outbox, analytic batches and ingestion quarantine (3 tables)';
COMMENT ON SCHEMA analitica IS 'Six dimensions, three facts and the publication marker (10 tables)';
