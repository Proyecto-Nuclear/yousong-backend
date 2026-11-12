package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.cola.ColaApiTestSupport;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Temporary and permanent content blocks of the owner: creation, effects on the queue, listing and lifting. */
class BloqueoContenidoApiTest extends ColaApiTestSupport {

    private static final String BLOQUEOS = "/api/mi-establecimiento/bloqueos";

    @Test
    void aTemporaryBlockIsStoredWithItsExpiryAuditedAndPublished() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        String video = videoGuardado();

        HttpResponse<String> response = bloquear(local.token(), video, 90, "DERECHOS");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode body = json(response);
        UUID id = UUID.fromString(body.get("id").asString());
        assertThat(body.get("videoId").asString()).isEqualTo(video);
        assertThat(body.get("motivoCodigo").asString()).isEqualTo("DERECHOS");
        assertThat(body.get("permanente").asBoolean()).isFalse();
        assertThat(body.get("vigente").asBoolean()).isTrue();
        assertThat(body.get("reproduciendoActualmente").asBoolean()).isFalse();
        assertThat(body.get("solicitudesBloqueadas").asInt()).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT (establecimiento_id = ?) || '|' || (cancion_id = ?) || '|' || motivo_codigo || '|'
                       || (hasta_at - desde_at = interval '90 minutes') || '|' || (levantado_at IS NULL)
                FROM operacion.bloqueo_contenido WHERE id = ?
                """, String.class, local.id(), cancionDeVideo(video), id)).isEqualTo("true|true|DERECHOS|true|true");
        assertThat(jdbc.queryForObject("""
                SELECT (payload ->> 'action') || '|' || (payload ->> 'reason_code') || '|' || (payload ->> 'until_at' IS NOT NULL)
                       || '|' || (sesion_id = ?)
                FROM integracion.evento_outbox WHERE event_type = 'moderation.changed' AND agregado_id = ?
                """, String.class, sesion, id)).isEqualTo("BLOCKED|DERECHOS|true|true");
        assertThat(contar("SELECT count(*) FROM operacion.auditoria WHERE establecimiento_id = ? "
                + "AND accion_codigo = 'CONTENT_BLOCKED' AND entidad_ref = ? AND resultado = 'EXITO'", local.id(), id))
                .isEqualTo(1);
    }

    @Test
    void aPermanentBlockHasNoExpiry() {
        Local local = localAbierto();
        String video = videoGuardado();

        HttpResponse<String> response = bloquear(local.token(), video, null, "INAPROPIADO");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(json(response).get("permanente").asBoolean()).isTrue();
        assertThat(json(response).get("hastaAt").isNull()).isTrue();
        assertThat(contar("SELECT count(*) FROM operacion.bloqueo_contenido WHERE id = ? AND hasta_at IS NULL",
                UUID.fromString(json(response).get("id").asString()))).isEqualTo(1);
    }

    @Test
    void aBlockWorksWithoutASessionInOperation() {
        Local local = localListoParaAbrir();
        String video = videoGuardado();

        assertThat(bloquear(local.token(), video, 30, "OTRO").statusCode()).isEqualTo(201);
        abrirSesion(local);

        assertProblema(solicitar(unirse(local), video), 409, "SONG_BLOCKED");
    }

    @Test
    void invalidBlocksAreRejected() {
        Local local = localAbierto();
        String video = videoGuardado();

        assertProblema(bloquear(local.token(), video, 0, "OTRO"), 400, "INVALID_BLOCK_DURATION");
        assertProblema(bloquear(local.token(), video, 10081, "OTRO"), 400, "INVALID_BLOCK_DURATION");
        assertProblema(post(BLOQUEOS, local.token(), "{\"videoId\":\"" + video + "\",\"motivoCodigo\":\"OTRO\"}"),
                400, "INVALID_BLOCK_DURATION");
        assertProblema(post(BLOQUEOS, local.token(), "{\"videoId\":\"" + video
                + "\",\"duracionMinutos\":5,\"permanente\":true,\"motivoCodigo\":\"OTRO\"}"), 400, "INVALID_BLOCK_DURATION");
        assertProblema(bloquear(local.token(), video, 10, "PORQUE_SI"), 400, "INVALID_BLOCK_REASON");
        assertProblema(post(BLOQUEOS, local.token(), "{\"videoId\":\"" + video + "\",\"duracionMinutos\":5}"),
                400, "INVALID_BLOCK_REASON");
        assertProblema(bloquear(local.token(), "corto", 10, "OTRO"), 400, "INVALID_VIDEO_ID");
        assertThat(contar("SELECT count(*) FROM operacion.bloqueo_contenido WHERE establecimiento_id = ?", local.id()))
                .isZero();
    }

    @Test
    void aSongCanOnlyHaveOneActiveBlock() {
        Local local = localAbierto();
        String video = videoGuardado();
        bloqueada(local.token(), video);

        assertProblema(bloquear(local.token(), video, 10, "OTRO"), 409, "SONG_ALREADY_BLOCKED");
    }

    @Test
    void aBlockedSongCannotBeRequestedNorVoted() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        String video = videoGuardado();
        UUID solicitud = solicitada(unirse(local), video);
        bloqueada(local.token(), video);
        int votosAntes = votos(solicitud);

        assertProblema(solicitar(unirse(local), video), 409, "SONG_BLOCKED");
        assertProblema(votar(unirse(local), solicitud), 409, "SONG_BLOCKED");
        assertThat(votos(solicitud)).isEqualTo(votosAntes);
        assertThat(contar("SELECT count(*) FROM operacion.solicitud WHERE sesion_id = ? AND cancion_id = ?", sesion,
                cancionDeVideo(video))).isEqualTo(1);
    }

    @Test
    void blockingMovesTheWaitingRequestsOfTheSongOutOfTheQueue() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        String video = videoGuardado();
        UUID bloqueadaSolicitud = solicitada(unirse(local), video);
        UUID otra = solicitada(unirse(local), videoGuardado());
        long antes = colaVersion(sesion);

        HttpResponse<String> response = bloquear(local.token(), video, 60, "INAPROPIADO");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(json(response).get("solicitudesBloqueadas").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT estado || '|' || (finalizada_at IS NOT NULL) FROM operacion.solicitud "
                + "WHERE id = ?", String.class, bloqueadaSolicitud)).isEqualTo("BLOQUEADA|true");
        assertThat(votos(bloqueadaSolicitud)).isEqualTo(1);
        assertThat(colaVersion(sesion)).isEqualTo(antes + 1);
        assertThat(jdbc.queryForObject("""
                SELECT (payload ->> 'reason_code') || '|' || (payload ->> 'queue_version') || '|' || (payload ->> 'queue_size')
                FROM integracion.evento_outbox WHERE sesion_id = ? AND event_type = 'queue.changed'
                ORDER BY agregado_version DESC LIMIT 1
                """, String.class, sesion)).isEqualTo("content_blocked|" + (antes + 1) + "|1");
        JsonNode cola = json(colaPublica(local.slug()));
        assertThat(cola.get("cola")).hasSize(1);
        assertThat(cola.get("cola").get(0).get("solicitudId").asString()).isEqualTo(otra.toString());
        assertThat(cola.get("colaVersion").asLong()).isEqualTo(antes + 1);
    }

    @Test
    void blockingASongWithoutWaitingRequestsDoesNotChangeTheQueueVersion() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        long antes = colaVersion(sesion);

        bloqueada(local.token(), videoGuardado());

        assertThat(colaVersion(sesion)).isEqualTo(antes);
    }

    @Test
    void blockingTheSongPlayingDoesNotInterruptItButReportsIt() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        String video = videoGuardado();
        UUID solicitud = solicitada(unirse(local), video);
        siguiente(local.token());

        HttpResponse<String> response = bloquear(local.token(), video, 60, "INAPROPIADO");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(json(response).get("reproduciendoActualmente").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT estado FROM operacion.solicitud WHERE id = ?", String.class, solicitud))
                .isEqualTo("REPRODUCIENDO");
        assertThat(videoSonando(sesion)).isEqualTo(video);
        assertProblema(votar(unirse(local), solicitud), 409, "SONG_BLOCKED");
    }

    @Test
    void anExpiredOrLiftedBlockNoLongerApplies() {
        Local local = localAbierto();
        String expira = videoGuardado();
        String levanta = videoGuardado();
        UUID vencido = bloqueada(local.token(), expira);
        UUID levantado = bloqueada(local.token(), levanta);
        jdbc.update("UPDATE operacion.bloqueo_contenido SET desde_at = now() - interval '2 hours', "
                + "hasta_at = now() - interval '1 hour' WHERE id = ?", vencido);

        HttpResponse<String> lift = delete(BLOQUEOS + "/" + levantado, local.token());

        assertThat(lift.statusCode()).as(lift.body()).isEqualTo(200);
        assertThat(json(lift).get("vigente").asBoolean()).isFalse();
        assertThat(json(lift).get("levantadoAt").isNull()).isFalse();
        assertThat(contar("SELECT count(*) FROM operacion.bloqueo_contenido WHERE id = ? AND levantado_at IS NOT NULL",
                levantado)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT payload ->> 'action' FROM integracion.evento_outbox
                WHERE event_type = 'moderation.changed' AND agregado_id = ? AND agregado_version = 1
                """, String.class, levantado)).isEqualTo("LIFTED");
        assertThat(contar("SELECT count(*) FROM operacion.auditoria WHERE accion_codigo = 'CONTENT_UNBLOCKED' "
                + "AND entidad_ref = ?", levantado)).isEqualTo(1);
        assertThat(solicitar(unirse(local), expira).statusCode()).isEqualTo(201);
        assertThat(solicitar(unirse(local), levanta).statusCode()).isEqualTo(201);
    }

    @Test
    void liftingIsIdempotentAndKeepsTheRecord() {
        Local local = localAbierto();
        UUID id = bloqueada(local.token(), videoGuardado());
        HttpResponse<String> primera = delete(BLOQUEOS + "/" + id, local.token());

        HttpResponse<String> segunda = delete(BLOQUEOS + "/" + id, local.token());

        assertThat(segunda.statusCode()).isEqualTo(200);
        assertThat(json(segunda).get("levantadoAt").asString()).isEqualTo(json(primera).get("levantadoAt").asString());
        assertThat(contar("SELECT count(*) FROM operacion.bloqueo_contenido WHERE id = ?", id)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM integracion.evento_outbox WHERE event_type = 'moderation.changed' "
                + "AND agregado_id = ?", id)).isEqualTo(2);
    }

    @Test
    void theListShowsActiveBlocksAndOptionallyTheHistory() {
        Local local = localAbierto();
        String activa = videoGuardado();
        UUID vigente = bloqueada(local.token(), activa);
        UUID levantado = bloqueada(local.token(), videoGuardado());
        UUID vencido = bloqueada(local.token(), videoGuardado());
        delete(BLOQUEOS + "/" + levantado, local.token());
        jdbc.update("UPDATE operacion.bloqueo_contenido SET desde_at = now() - interval '2 hours', "
                + "hasta_at = now() - interval '1 hour' WHERE id = ?", vencido);

        JsonNode activos = json(get(BLOQUEOS, local.token()));
        JsonNode todos = json(get(BLOQUEOS + "?todos=true", local.token()));

        assertThat(ids(activos)).containsExactly(vigente.toString());
        assertThat(activos.get(0).get("videoId").asString()).isEqualTo(activa);
        assertThat(activos.get(0).get("hastaAt").isNull()).isFalse();
        assertThat(activos.get(0).get("cancion").get("titulo").asString()).isEqualTo("Titulo " + activa);
        assertThat(ids(todos)).containsExactlyInAnyOrder(vigente.toString(), levantado.toString(), vencido.toString());
    }

    @Test
    void blocksOfOneEstablishmentNeverReachAnother() {
        Local ana = localAbierto();
        Local luis = localAbierto();
        String video = videoGuardado();
        UUID deAna = bloqueada(ana.token(), video);

        assertThat(solicitar(unirse(luis), video).statusCode()).isEqualTo(201);
        assertThat(json(get(BLOQUEOS + "?todos=true", luis.token()))).isEmpty();
        assertProblema(delete(BLOQUEOS + "/" + deAna, luis.token()), 404, "BLOCK_NOT_FOUND");
        assertThat(contar("SELECT count(*) FROM operacion.bloqueo_contenido WHERE id = ? AND levantado_at IS NULL",
                deAna)).isEqualTo(1);
        assertProblema(get(BLOQUEOS, tokenSinEstablecimiento()), 404, "NO_ESTABLISHMENT");
        assertThat(get(BLOQUEOS, unirse(ana)).statusCode()).isEqualTo(401);
        assertThat(bloquear(null, video, 10, "OTRO").statusCode()).isEqualTo(401);
    }

    @Test
    void anUnknownSongCannotBeBlockedWithoutTheCatalog() {
        Local local = localAbierto();

        HttpResponse<String> response = bloquear(local.token(), videoIdAleatorio(), 10, "OTRO");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(contar("SELECT count(*) FROM operacion.bloqueo_contenido WHERE establecimiento_id = ?", local.id()))
                .isZero();
    }

    @Test
    void aBlockRacingVotesNeverLeavesAVoteOnABlockedRequest() throws Exception {
        for (int ronda = 0; ronda < 5; ronda++) {
            Local local = localAbierto();
            UUID sesion = sesionDe(local);
            String video = videoGuardado();
            UUID solicitud = solicitada(unirse(local), video);
            List<String> votantes = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                votantes.add(unirse(local));
            }
            CountDownLatch salida = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(5);
            List<HttpResponse<String>> respuestas = new ArrayList<>();
            HttpResponse<String> bloqueo;
            try {
                List<Future<HttpResponse<String>>> futuras = new ArrayList<>();
                for (String votante : votantes) {
                    futuras.add(pool.submit(() -> {
                        salida.await();
                        return votar(votante, solicitud);
                    }));
                }
                Future<HttpResponse<String>> futuroBloqueo = pool.submit(() -> {
                    salida.await();
                    return bloquear(local.token(), video, 10, "INAPROPIADO");
                });
                salida.countDown();
                for (Future<HttpResponse<String>> futura : futuras) {
                    respuestas.add(futura.get());
                }
                bloqueo = futuroBloqueo.get();
            } finally {
                pool.shutdownNow();
            }

            assertThat(bloqueo.statusCode()).as(bloqueo.body()).isEqualTo(201);
            assertThat(respuestas).extracting(HttpResponse::statusCode).allMatch(s -> s == 200 || s == 409);
            long aceptados = respuestas.stream().filter(r -> r.statusCode() == 200).count();
            assertThat(votos(solicitud)).isEqualTo(1 + (int) aceptados);
            assertThat(jdbc.queryForObject("SELECT estado FROM operacion.solicitud WHERE id = ?", String.class, solicitud))
                    .isEqualTo("BLOQUEADA");
            OffsetFinal fin = jdbc.queryForObject("""
                    SELECT (SELECT max(v.creado_at) FROM operacion.voto v WHERE v.solicitud_id = s.id) AS ultimo_voto,
                           s.finalizada_at FROM operacion.solicitud s WHERE s.id = ?
                    """, (fila, n) -> new OffsetFinal(fila.getTimestamp("ultimo_voto"), fila.getTimestamp("finalizada_at")),
                    solicitud);
            assertThat(fin.ultimoVoto()).isBeforeOrEqualTo(fin.finalizada());
            assertThat(contar("SELECT count(*) FROM operacion.solicitud WHERE sesion_id = ? AND estado = 'PENDIENTE'",
                    sesion)).isZero();
        }
    }

    private record OffsetFinal(java.sql.Timestamp ultimoVoto, java.sql.Timestamp finalizada) {
    }

    private static List<String> ids(JsonNode lista) {
        List<String> ids = new ArrayList<>();
        lista.forEach(item -> ids.add(item.get("id").asString()));
        return ids;
    }
}
