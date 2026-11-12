-- Device identification without personal data: the client keeps an opaque random secret, the server keeps
-- only its SHA-256 (hex). No MAC, IMEI, IP, user agent or fingerprint is involved. Rows created before this
-- migration keep NULL: they can never be identified again, which only means their phone gets a new device.
ALTER TABLE operacion.dispositivo ADD COLUMN credencial_hash char(64);
ALTER TABLE operacion.dispositivo ADD CONSTRAINT ck_dispositivo_credencial_hash
    CHECK (credencial_hash ~ '^[0-9a-f]{64}$');
CREATE UNIQUE INDEX uq_dispositivo_credencial_hash ON operacion.dispositivo (credencial_hash);

-- A device now outlives its sessions and holds one participation per session, so a participant credential
-- names the participation it opens instead of deriving it from the device.
ALTER TABLE operacion.sesion_acceso ADD COLUMN participacion_id uuid;
ALTER TABLE operacion.sesion_acceso ADD CONSTRAINT fk_sesion_acceso_participacion
    FOREIGN KEY (participacion_id) REFERENCES operacion.participacion (id);
ALTER TABLE operacion.sesion_acceso ADD CONSTRAINT ck_sesion_acceso_participacion
    CHECK (participacion_id IS NULL OR dispositivo_id IS NOT NULL);
CREATE INDEX ix_sesion_acceso_participacion ON operacion.sesion_acceso (participacion_id);

-- Until now every join registered its own device, so each participant credential belongs to the single
-- participation of its device.
UPDATE operacion.sesion_acceso a SET participacion_id = p.id
FROM operacion.participacion p
WHERE a.dispositivo_id IS NOT NULL AND a.participacion_id IS NULL AND p.dispositivo_id = a.dispositivo_id;
