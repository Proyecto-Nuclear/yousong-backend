-- Owner-controlled operational flags of an establishment. Additive: existing rows default to "running
-- normally" (not paused, not in maintenance), so nothing changes until an owner uses them.
-- pausado: system pause (no joins, no new sessions, no requests or votes); independent of activo.
-- en_mantenimiento: maintenance between operating days (no joins, no new sessions).
ALTER TABLE operacion.establecimiento
    ADD COLUMN pausado          boolean NOT NULL DEFAULT false,
    ADD COLUMN en_mantenimiento boolean NOT NULL DEFAULT false;
