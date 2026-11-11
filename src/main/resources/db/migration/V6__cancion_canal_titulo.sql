-- Provider metadata: the YouTube channel that published the video.
-- It is NOT the artist (see cancion.artista_verificado, which only holds a verified artist). It is stored so a
-- list can show "who published this" without calling the provider again, and it follows the same
-- revalidation/expiry rules as the other provider metadata (metadatos_expiran_at).
ALTER TABLE operacion.cancion ADD COLUMN canal_titulo varchar(200);
COMMENT ON COLUMN operacion.cancion.canal_titulo IS 'Channel that published the video (provider metadata, not a verified artist)';
