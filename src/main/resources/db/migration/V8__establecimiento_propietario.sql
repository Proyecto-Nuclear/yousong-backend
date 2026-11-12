-- Ownership: one identity-provider account (the access token "sub") owns at most one establishment.
-- Additive: establishments created before this migration keep a NULL owner, so no account can reach them
-- through the owner API until an owner is assigned by hand. Several NULL owners may coexist.
ALTER TABLE operacion.establecimiento
    ADD COLUMN propietario_sub varchar(64);

CREATE UNIQUE INDEX ux_establecimiento_propietario_sub
    ON operacion.establecimiento (propietario_sub)
    WHERE propietario_sub IS NOT NULL;
