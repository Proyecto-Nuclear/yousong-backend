package cue.edu.co.yousongbackend.reproduccion;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.cola.ColaApiTestSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The minimal staff playback driver: take the head of the queue, then end it. */
class ReproduccionStaffApiTest extends ColaApiTestSupport {

    @Test
    void nextTakesTheMostVotedRequestAndShowsItAsNowPlaying() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        UUID primera = solicitada(unirse(local), videoGuardado());
        UUID popular = solicitada(unirse(local), videoGuardado());
        votar(unirse(local), popular);
        long antes = colaVersion(sesion);

        HttpResponse<String> response = siguiente(sesion, operatorToken());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("solicitudId").asString()).isEqualTo(popular.toString());
        assertThat(body.get("votos").asInt()).isEqualTo(2);
        assertThat(body.get("colaVersion").asLong()).isEqualTo(antes + 1);
        assertThat(colaVersion(sesion)).isEqualTo(antes + 1);
        assertThat(jdbc.queryForObject("SELECT estado FROM operacion.solicitud WHERE id = ?", String.class, popular))
                .isEqualTo("REPRODUCIENDO");
        assertThat(jdbc.queryForObject("""
                SELECT origen || '|' || (solicitud_id = ?) || '|' || (iniciada_at IS NOT NULL) || '|' || (terminada_at IS NULL)
                FROM operacion.reproduccion WHERE sesion_id = ?
                """, String.class, popular, sesion)).isEqualTo("SOLICITUD|true|true|true");
        assertThat(jdbc.queryForObject("""
                SELECT (payload ->> 'request_id') || '|' || (payload ->> 'votes_at_start')
                FROM integracion.evento_outbox WHERE sesion_id = ? AND event_type = 'playback.started'
                """, String.class, sesion)).isEqualTo(popular + "|2");

        JsonNode cola = json(colaPublica(local.slug()));
        assertThat(cola.get("enReproduccion").get("solicitudId").asString()).isEqualTo(popular.toString());
        assertThat(cola.get("enReproduccion").get("restanteSegundos").asLong()).isBetween(195L, 200L);
        assertThat(cola.get("cola")).hasSize(1);
        assertThat(cola.get("cola").get(0).get("solicitudId").asString()).isEqualTo(primera.toString());
    }

    @Test
    void finishingEndsThePlaybackAndTheRequest() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        UUID solicitud = solicitada(unirse(local), videoGuardado());
        siguiente(sesion, operatorToken());
        long antes = colaVersion(sesion);

        HttpResponse<String> response = fin(sesion, "COMPLETADA", adminToken());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).get("resultado").asString()).isEqualTo("COMPLETADA");
        assertThat(json(response).get("colaVersion").asLong()).isEqualTo(antes + 1);
        assertThat(jdbc.queryForObject("SELECT estado || '|' || (finalizada_at IS NOT NULL) FROM operacion.solicitud "
                + "WHERE id = ?", String.class, solicitud)).isEqualTo("ATENDIDA|true");
        assertThat(jdbc.queryForObject("SELECT resultado || '|' || (terminada_at IS NOT NULL) FROM operacion.reproduccion "
                + "WHERE solicitud_id = ?", String.class, solicitud)).isEqualTo("COMPLETADA|true");
        assertThat(jdbc.queryForObject("""
                SELECT (payload ->> 'request_id') || '|' || (payload ->> 'resultado')
                FROM integracion.evento_outbox WHERE sesion_id = ? AND event_type = 'playback.finished'
                """, String.class, sesion)).isEqualTo(solicitud + "|COMPLETADA");
        assertThat(json(colaPublica(local.slug())).get("enReproduccion").isNull()).isTrue();
    }

    @Test
    void skippedAndFailedPlaybacksMarkTheRequestTheSameWay() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        UUID saltada = solicitada(unirse(local), videoGuardado());
        UUID fallida = solicitada(unirse(local), videoGuardado());

        siguiente(sesion, operatorToken());
        assertThat(fin(sesion, "SALTADA", operatorToken()).statusCode()).isEqualTo(200);
        siguiente(sesion, operatorToken());
        assertThat(fin(sesion, "FALLIDA", operatorToken()).statusCode()).isEqualTo(200);

        assertThat(jdbc.queryForObject("SELECT estado FROM operacion.solicitud WHERE id = ?", String.class, saltada))
                .isEqualTo("SALTADA");
        assertThat(jdbc.queryForObject("SELECT estado FROM operacion.solicitud WHERE id = ?", String.class, fallida))
                .isEqualTo("FALLIDA");
    }

    @Test
    void aSongThatJustPlayedWaits30MinutesToBeRequestedAgain() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        String video = videoGuardado();
        solicitada(unirse(local), video);
        siguiente(sesion, operatorToken());
        fin(sesion, "COMPLETADA", operatorToken());

        assertProblema(solicitar(unirse(local), video), 409, "SONG_RECENTLY_PLAYED");
    }

    @Test
    void theStaffDriverCreatesAStationOnlyOnceAndReusesTheEnabledOne() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        solicitada(unirse(local), videoGuardado());
        solicitada(unirse(local), videoGuardado());

        siguiente(sesion, operatorToken());
        fin(sesion, "COMPLETADA", operatorToken());
        siguiente(sesion, operatorToken());

        assertThat(contar("SELECT count(*) FROM operacion.estacion WHERE sesion_id = ?", sesion)).isEqualTo(1);
        assertThat(contar("SELECT count(DISTINCT estacion_id) FROM operacion.reproduccion WHERE sesion_id = ?", sesion))
                .isEqualTo(1);

        Local otro = localAbierto();
        UUID otraSesion = sesionDe(otro);
        UUID habilitada = estacionHabilitada(otro.id(), otraSesion);
        solicitada(unirse(otro), videoGuardado());
        siguiente(otraSesion, operatorToken());
        assertThat(jdbc.queryForObject("SELECT estacion_id FROM operacion.reproduccion WHERE sesion_id = ?", UUID.class,
                otraSesion)).isEqualTo(habilitada);
    }

    @Test
    void nextWhilePlayingOrWithAnEmptyQueueIsAConflict() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);

        assertProblema(siguiente(sesion, operatorToken()), 409, "QUEUE_EMPTY");
        solicitada(unirse(local), videoGuardado());
        solicitada(unirse(local), videoGuardado());
        siguiente(sesion, operatorToken());
        long version = colaVersion(sesion);

        assertProblema(siguiente(sesion, operatorToken()), 409, "PLAYBACK_IN_PROGRESS");
        assertThat(colaVersion(sesion)).isEqualTo(version);
    }

    @Test
    void finishingWithoutAPlaybackOrForAnUnknownOrClosedSessionIsRejected() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);

        assertProblema(fin(sesion, "COMPLETADA", operatorToken()), 409, "NO_ACTIVE_PLAYBACK");
        assertProblema(fin(UUID.randomUUID(), "COMPLETADA", operatorToken()), 404, "SESSION_NOT_FOUND");
        assertProblema(siguiente(UUID.randomUUID(), operatorToken()), 404, "SESSION_NOT_FOUND");
        cerrarSesion(sesion);
        assertProblema(siguiente(sesion, operatorToken()), 409, "SESSION_NOT_IN_OPERATION");
    }

    @Test
    void theResultMustBeOneOfTheThreeOutcomes() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        solicitada(unirse(local), videoGuardado());
        siguiente(sesion, operatorToken());

        assertProblema(fin(sesion, "INTERRUMPIDA_CIERRE", operatorToken()), 400, "INVALID_BODY");
        assertProblema(post("/api/staff/sesiones/" + sesion + "/reproduccion/fin", operatorToken(), "{}"), 400,
                "VALIDATION_ERROR");
        assertThat(contar("SELECT count(*) FROM operacion.reproduccion WHERE sesion_id = ? AND terminada_at IS NULL",
                sesion)).isEqualTo(1);
    }

    @Test
    void onlyStaffDrivesPlayback() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        String participante = unirse(local);
        solicitada(participante, videoGuardado());

        assertThat(siguiente(sesion, null).statusCode()).isEqualTo(401);
        assertThat(siguiente(sesion, participante).statusCode()).isEqualTo(401);
        assertThat(fin(sesion, "COMPLETADA", participante).statusCode()).isEqualTo(401);
        assertThat(contar("SELECT count(*) FROM operacion.reproduccion WHERE sesion_id = ?", sesion)).isZero();
    }

    @Test
    void twoParallelStartsPlayOnlyOneSong() throws Exception {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        solicitada(unirse(local), videoGuardado());
        solicitada(unirse(local), videoGuardado());
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<HttpResponse<String>> respuestas;
        try {
            List<Future<HttpResponse<String>>> futuras = List.of(
                    pool.submit(() -> {
                        salida.await();
                        return siguiente(sesion, operatorToken());
                    }),
                    pool.submit(() -> {
                        salida.await();
                        return siguiente(sesion, adminToken());
                    }));
            salida.countDown();
            respuestas = List.of(futuras.get(0).get(), futuras.get(1).get());
        } finally {
            pool.shutdownNow();
        }

        assertThat(respuestas).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(200, 409);
        assertThat(contar("SELECT count(*) FROM operacion.reproduccion WHERE sesion_id = ?", sesion)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM operacion.solicitud WHERE sesion_id = ? AND estado = 'REPRODUCIENDO'",
                sesion)).isEqualTo(1);
    }

    @Test
    void closingTheSessionInterruptsThePlaybackAndEmptiesTheQueue() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        solicitada(unirse(local), videoGuardado());
        siguiente(sesion, operatorToken());

        cerrarSesion(sesion);

        assertThat(jdbc.queryForObject("SELECT resultado FROM operacion.reproduccion WHERE sesion_id = ?", String.class,
                sesion)).isEqualTo("INTERRUMPIDA_CIERRE");
        JsonNode cola = json(colaPublica(local.slug()));
        assertThat(cola.get("estado").asString()).isEqualTo("CERRADO");
        assertThat(cola.get("enReproduccion").isNull()).isTrue();
    }
}
