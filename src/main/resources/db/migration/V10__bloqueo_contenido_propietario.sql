-- Content blocks are placed by the establishment owner, an identity-provider account that has no row in
-- operacion.personal (the owner model never creates staff accounts). The author of a block is therefore the
-- owner of its establishment (one owner per establishment, see V8) and personal_id stays for staff accounts.
-- Additive: existing rows keep their author; only the NOT NULL requirement is relaxed.
ALTER TABLE operacion.bloqueo_contenido
    ALTER COLUMN personal_id DROP NOT NULL;
