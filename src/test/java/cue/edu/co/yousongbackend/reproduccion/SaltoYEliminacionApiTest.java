package cue.edu.co.yousongbackend.reproduccion;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.cola.ColaApiTestSupport;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Skipping the playing song with a recorded reason, and removing a waiting request from the queue. */
class SaltoYEliminacionApiTest extends ColaApiTestSupport {

    @Test
    void aSkipRecordsItsReasonAndAnAuditRow() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        UUID solicitud = solicitada(unirse(local), videoGuardado());
        siguiente(local.token());

        HttpResponse<String> response = finSaltada("PROBLEMA_TECNICO", local.token());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        UUID reproduccion = UUID.fromString(json(response).get("reproduccionId").asString());
        assertThat(json(response).get("motivoCodigo").asString()).isEqualTo("PROBLEMA_TECNICO");
        assertThat(jdbc.queryForObject("SELECT resultado || '|' || motivo_codigo FROM operacion.reproduccion WHERE id = ?",
                String.class, reproduccion)).isEqualTo("SALTADA|PROBLEMA_TECNICO");
        assertThat(jdbc.queryForObject("SELECT estado FROM operacion.solicitud WHERE id = ?", String.class, solicitud))
                .isEqualTo("SALTADA");
        assertThat(contar("SELECT count(*) FROM operacion.auditoria WHERE establecimiento_id = ? "
                + "AND accion_codigo = 'PLAYBACK_SKIPPED' AND entidad_tipo = 'REPRODUCCION' AND entidad_ref = ?",
                local.id(), reproduccion)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT payload ->> 'reason_code' FROM integracion.evento_outbox
                WHERE sesion_id = ? AND event_type = 'playback.finished'
                """, String.class, sesion)).isEqualTo("PROBLEMA_TECNICO");
    }

    @Test
    void aSkipWithoutReasonIsAManualSkip() {
        Local local = localAbierto();
        solicitada(unirse(local), videoGuardado());
        siguiente(local.token());

        HttpResponse<String> response = finSaltada(null, local.token());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT motivo_codigo FROM operacion.reproduccion WHERE id = ?", String.class,
                UUID.fromString(json(response).get("reproduccionId").asString()))).isEqualTo("SALTO_MANUAL");
    }

    @Test
    void completedAndFailedPlaybacksKeepNoReasonAndRejectOne() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        solicitada(unirse(local), videoGuardado());
        siguiente(local.token());

        assertProblema(post("/api/mi-establecimiento/reproduccion/fin", local.token(),
                "{\"resultado\":\"COMPLETADA\",\"motivoCodigo\":\"SALTO_MANUAL\"}"), 400, "SKIP_REASON_NOT_ALLOWED");
        assertProblema(finSaltada("ME_ABURRE", local.token()), 400, "INVALID_SKIP_REASON");
        HttpResponse<String> completada = fin("COMPLETADA", local.token());

        assertThat(completada.statusCode()).isEqualTo(200);
        assertThat(json(completada).get("motivoCodigo").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT (motivo_codigo IS NULL) FROM operacion.reproduccion WHERE sesion_id = ?",
                Boolean.class, sesion)).isTrue();
        assertThat(contar("SELECT count(*) FROM operacion.auditoria WHERE establecimiento_id = ?", local.id())).isZero();
    }

    @Test
    void skipEndsTheSongAndStartsTheNextRequestInOneStep() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        UUID primera = solicitada(unirse(local), videoGuardado());
        UUID segunda = solicitada(unirse(local), videoGuardado());
        siguiente(local.token());
        long antes = colaVersion(sesion);

        HttpResponse<String> response = saltar("SALTO_MANUAL", local.token());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("fin").get("solicitudId").asString()).isEqualTo(primera.toString());
        assertThat(body.get("fin").get("resultado").asString()).isEqualTo("SALTADA");
        assertThat(body.get("siguiente").get("solicitudId").asString()).isEqualTo(segunda.toString());
        assertThat(colaVersion(sesion)).isEqualTo(antes + 1);
        assertThat(jdbc.queryForObject("SELECT estado FROM operacion.solicitud WHERE id = ?", String.class, primera))
                .isEqualTo("SALTADA");
        assertThat(jdbc.queryForObject("SELECT estado FROM operacion.solicitud WHERE id = ?", String.class, segunda))
                .isEqualTo("REPRODUCIENDO");
        assertThat(contar("SELECT count(*) FROM operacion.auditoria WHERE accion_codigo = 'PLAYBACK_SKIPPED' "
                + "AND establecimiento_id = ?", local.id())).isEqualTo(1);
    }

    @Test
    void skipFallsBackToTheDefaultListAndToNothing() {
        String a = videoGuardado();
        Local local = localConLista("SECUENCIAL", a);
        UUID sesion = sesionDe(local);
        solicitada(unirse(local), videoGuardado());
        siguiente(local.token());

        JsonNode haciaDefecto = json(saltar(null, local.token()));
        assertThat(haciaDefecto.get("siguiente").get("origen").asString()).isEqualTo("DEFECTO");
        assertThat(haciaDefecto.get("siguiente").get("cancion").get("videoId").asString()).isEqualTo(a);

        Local sinLista = localAbierto();
        solicitada(unirse(sinLista), videoGuardado());
        siguiente(sinLista.token());
        HttpResponse<String> ultima = saltar(null, sinLista.token());
        assertThat(ultima.statusCode()).as(ultima.body()).isEqualTo(200);
        assertThat(json(ultima).get("siguiente").isNull()).isTrue();
        assertThat(json(colaPublica(sinLista.slug())).get("enReproduccion").isNull()).isTrue();
        assertThat(eventos(sesion, "playback.source_changed")).isEqualTo(2);
    }

    @Test
    void skipWithoutAPlaybackOrWithABadReasonIsRejected() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);

        assertProblema(saltar(null, local.token()), 409, "NO_ACTIVE_PLAYBACK");
        solicitada(unirse(local), videoGuardado());
        siguiente(local.token());
        assertProblema(saltar("NADA", local.token()), 400, "INVALID_SKIP_REASON");
        assertThat(contar("SELECT count(*) FROM operacion.reproduccion WHERE sesion_id = ? AND terminada_at IS NULL",
                sesion)).isEqualTo(1);
        assertProblema(saltar(null, tokenSinEstablecimiento()), 404, "NO_ESTABLISHMENT");
        assertThat(saltar(null, unirse(local)).statusCode()).isEqualTo(401);
    }

    @Test
    void removingAWaitingRequestMarksItSkippedAndKeepsItsVotes() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        UUID solicitud = solicitada(unirse(local), videoGuardado());
        votar(unirse(local), solicitud);
        UUID otra = solicitada(unirse(local), videoGuardado());
        long antes = colaVersion(sesion);

        HttpResponse<String> response = eliminarDeCola(solicitud, local.token());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).get("estado").asString()).isEqualTo("SALTADA");
        assertThat(json(response).get("motivoCodigo").asString()).isEqualTo("ELIMINADA_POR_PERSONAL");
        assertThat(json(response).get("colaVersion").asLong()).isEqualTo(antes + 1);
        assertThat(jdbc.queryForObject("SELECT estado || '|' || (finalizada_at IS NOT NULL) FROM operacion.solicitud "
                + "WHERE id = ?", String.class, solicitud)).isEqualTo("SALTADA|true");
        assertThat(votos(solicitud)).isEqualTo(2);
        assertThat(colaVersion(sesion)).isEqualTo(antes + 1);
        assertThat(jdbc.queryForObject("""
                SELECT (payload ->> 'reason_code') || '|' || (payload ->> 'queue_size')
                FROM integracion.evento_outbox WHERE sesion_id = ? AND event_type = 'queue.changed'
                  AND agregado_version = ?
                """, String.class, sesion, antes + 1)).isEqualTo("request_removed|1");
        assertThat(jdbc.queryForObject("""
                SELECT payload ->> 'reason_code' FROM integracion.evento_outbox
                WHERE event_type = 'request.removed' AND agregado_id = ?
                """, String.class, solicitud)).isEqualTo("ELIMINADA_POR_PERSONAL");
        assertThat(contar("SELECT count(*) FROM operacion.auditoria WHERE accion_codigo = 'REQUEST_REMOVED' "
                + "AND entidad_ref = ? AND establecimiento_id = ?", solicitud, local.id())).isEqualTo(1);
        JsonNode cola = json(colaPublica(local.slug()));
        assertThat(cola.get("cola")).hasSize(1);
        assertThat(cola.get("cola").get(0).get("solicitudId").asString()).isEqualTo(otra.toString());
    }

    @Test
    void onlyAWaitingRequestOfTheOwnSessionCanBeRemoved() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        UUID sonando = solicitada(unirse(local), videoGuardado());
        siguiente(local.token());
        UUID terminada = solicitada(unirse(local), videoGuardado());
        fin("COMPLETADA", local.token());
        siguiente(local.token());
        fin("COMPLETADA", local.token());
        UUID enCurso = solicitada(unirse(local), videoGuardado());
        siguiente(local.token());

        assertProblema(eliminarDeCola(enCurso, local.token()), 409, "REQUEST_NOT_ACTIVE");
        assertProblema(eliminarDeCola(terminada, local.token()), 409, "REQUEST_NOT_ACTIVE");
        assertProblema(eliminarDeCola(sonando, local.token()), 409, "REQUEST_NOT_ACTIVE");
        assertProblema(eliminarDeCola(UUID.randomUUID(), local.token()), 404, "REQUEST_NOT_FOUND");
        long version = colaVersion(sesion);

        Local otro = localAbierto();
        UUID ajena = solicitada(unirse(otro), videoGuardado());
        assertProblema(eliminarDeCola(ajena, local.token()), 404, "REQUEST_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT estado FROM operacion.solicitud WHERE id = ?", String.class, ajena))
                .isEqualTo("PENDIENTE");
        assertThat(colaVersion(sesion)).isEqualTo(version);
        assertThat(eliminarDeCola(ajena, unirse(otro)).statusCode()).isEqualTo(401);
        assertProblema(eliminarDeCola(ajena, tokenSinEstablecimiento()), 404, "NO_ESTABLISHMENT");
    }

    @Test
    void aRemovedRequestCanNoLongerBeVoted() {
        Local local = localAbierto();
        UUID solicitud = solicitada(unirse(local), videoGuardado());
        eliminarDeCola(solicitud, local.token());

        assertProblema(votar(unirse(local), solicitud), 409, "REQUEST_NOT_ACTIVE");
    }
}
