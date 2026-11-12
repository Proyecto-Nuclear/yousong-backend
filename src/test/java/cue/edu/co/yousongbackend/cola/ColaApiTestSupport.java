package cue.edu.co.yousongbackend.cola;

import cue.edu.co.yousongbackend.participante.ParticipanteApiTestSupport;
import java.net.http.HttpResponse;
import java.util.UUID;

/** Helpers for the queue tests: stored songs, requests, votes, the queue views and the staff playback driver. */
public abstract class ColaApiTestSupport extends ParticipanteApiTestSupport {

    protected static final String SOLICITUDES = "/api/participante/solicitudes";
    protected static final String COLA_PARTICIPANTE = "/api/participante/cola";

    /** A song already in the catalog with full metadata (no provider call is needed to request it). */
    protected UUID cancionGuardada(String videoId, String titulo, String canal, int duracionOperativa) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO operacion.cancion (id, youtube_video_id, titulo, canal_titulo, miniatura_url, "
                        + "duracion_proveedor_s, duracion_operativa_s, disponibilidad, validado_at, metadatos_expiran_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'DISPONIBLE', now(), now() + interval '7 days')",
                id, videoId, titulo, canal, "https://img.test/" + videoId + "/mqdefault.jpg", duracionOperativa,
                duracionOperativa);
        return id;
    }

    /** A stored song with a fresh random video id; returns that video id. */
    protected String videoGuardado() {
        String videoId = videoIdAleatorio();
        cancionGuardada(videoId, "Titulo " + videoId, "Canal - Topic", 200);
        return videoId;
    }

    protected HttpResponse<String> solicitar(String token, String videoId) {
        return post(SOLICITUDES, token, "{\"videoId\":\"" + videoId + "\"}");
    }

    protected HttpResponse<String> votar(String token, Object solicitudId) {
        return post(SOLICITUDES + "/" + solicitudId + "/votos", token);
    }

    /** Requests the song and returns the id of the request that now holds the vote. */
    protected UUID solicitada(String token, String videoId) {
        HttpResponse<String> response = solicitar(token, videoId);
        if (response.statusCode() != 201 && response.statusCode() != 200) {
            throw new AssertionError("request failed: " + response.statusCode() + " " + response.body());
        }
        return UUID.fromString(json(response).get("solicitudId").asString());
    }

    protected HttpResponse<String> colaPublica(String slug) {
        return get("/api/public/establecimientos/" + slug + "/cola", null);
    }

    protected HttpResponse<String> colaParticipante(String token) {
        return get(COLA_PARTICIPANTE, token);
    }

    /** Starts the next song of the caller's session in operation (the bearer's own establishment). */
    protected HttpResponse<String> siguiente(String bearer) {
        return post("/api/mi-establecimiento/reproduccion/siguiente", bearer);
    }

    protected HttpResponse<String> fin(String resultado, String bearer) {
        return post("/api/mi-establecimiento/reproduccion/fin", bearer, "{\"resultado\":\"" + resultado + "\"}");
    }

    protected UUID sesionDe(Local local) {
        return jdbc.queryForObject("SELECT id FROM operacion.sesion WHERE establecimiento_id = ? "
                + "AND estado IN ('ABIERTA', 'PAUSADA', 'CERRANDO')", UUID.class, local.id());
    }

    protected long colaVersion(UUID sesionId) {
        Long version = jdbc.queryForObject("SELECT cola_version FROM operacion.sesion WHERE id = ?", Long.class, sesionId);
        return version == null ? -1 : version;
    }

    protected int votos(UUID solicitudId) {
        return contar("SELECT count(*) FROM operacion.voto WHERE solicitud_id = ?", solicitudId);
    }

    protected int eventos(UUID sesionId, String tipo) {
        return contar("SELECT count(*) FROM integracion.evento_outbox WHERE sesion_id = ? AND event_type = ?",
                sesionId, tipo);
    }
}
