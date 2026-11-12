package cue.edu.co.yousongbackend.cola;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A participant requesting songs and supporting requests: "Crear una solicitud registra también su primer voto",
 * "Un apoyo por participante y video en cada sesión", and no vote change in the PMV.
 */
class SolicitudesApiTest extends ColaApiTestSupport {

    // ---------- requesting ----------

    @Test
    void requestingANewSongCreatesTheRequestWithTheCreatorsFirstVote() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        String token = unirse(local);
        String video = videoGuardado();

        HttpResponse<String> response = solicitar(token, video);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode body = json(response);
        UUID solicitudId = UUID.fromString(body.get("solicitudId").asString());
        assertThat(body.get("votos").asInt()).isEqualTo(1);
        assertThat(body.get("votado").asBoolean()).isTrue();
        assertThat(body.get("posicion").asInt()).isEqualTo(1);
        assertThat(body.get("creada").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT estado || '|' || ciclo FROM operacion.solicitud WHERE id = ?",
                String.class, solicitudId)).isEqualTo("PENDIENTE|1");
        assertThat(jdbc.queryForObject("SELECT es_inicial FROM operacion.voto WHERE solicitud_id = ?",
                Boolean.class, solicitudId)).isTrue();
        assertThat(contar("SELECT count(*) FROM operacion.solicitud WHERE sesion_id = ?", sesion)).isEqualTo(1);
    }

    @Test
    void requestingASongThatIsAlreadyActiveIsAVoteOnThatRequest() {
        Local local = localAbierto();
        String video = videoGuardado();
        UUID primera = solicitada(unirse(local), video);

        HttpResponse<String> response = solicitar(unirse(local), video);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("solicitudId").asString()).isEqualTo(primera.toString());
        assertThat(body.get("votos").asInt()).isEqualTo(2);
        assertThat(body.get("creada").asBoolean()).isFalse();
        assertThat(body.get("votado").asBoolean()).isTrue();
        assertThat(contar("SELECT count(*) FROM operacion.solicitud WHERE id = ?", primera)).isEqualTo(1);
        assertThat(contar("SELECT count(*) FROM operacion.voto WHERE solicitud_id = ? AND NOT es_inicial", primera))
                .isEqualTo(1);
    }

    @Test
    void theCreatorRequestingTheSameSongAgainIsAlreadyVoted() {
        Local local = localAbierto();
        String token = unirse(local);
        String video = videoGuardado();
        UUID solicitud = solicitada(token, video);

        assertProblema(solicitar(token, video), 409, "ALREADY_VOTED");
        assertThat(votos(solicitud)).isEqualTo(1);
    }

    @Test
    void anInvalidVideoIdIsABadRequest() {
        String token = unirse(localAbierto());

        assertProblema(solicitar(token, "not a video"), 400, "INVALID_VIDEO_ID");
        assertProblema(post(SOLICITUDES, token, "{}"), 400, "INVALID_VIDEO_ID");
    }

    @Test
    void aHugeInvalidVideoIdIsNotEchoedBack() {
        String token = unirse(localAbierto());
        String enorme = "A\\u0001" + "x".repeat(2 * 1024 * 1024);

        HttpResponse<String> response = post(SOLICITUDES, token, "{\"videoId\":\"" + enorme + "\"}");

        assertProblema(response, 400, "INVALID_VIDEO_ID");
        assertThat(response.body().length()).isLessThan(1024);
        String detalle = json(response).get("detail").asString();
        assertThat(detalle).startsWith("'A?" + "x".repeat(18) + "…'").doesNotContain("\u0001");
    }

    @Test
    void aShortInvalidVideoIdIsStillNamedInFull() {
        HttpResponse<String> response = solicitar(unirse(localAbierto()), "not a video");

        assertThat(json(response).get("detail").asString()).startsWith("'not a video' is not");
    }

    @Test
    void anUnknownSongNeedsTheCatalogWhichIsNotConfiguredHere() {
        String token = unirse(localAbierto());
        String video = videoIdAleatorio();

        assertProblema(solicitar(token, video), 503, "CATALOG_NOT_CONFIGURED");
        assertThat(contar("SELECT count(*) FROM operacion.cancion WHERE youtube_video_id = ?", video)).isZero();
    }

    @Test
    void anUnavailableSongCannotBeRequested() {
        Local local = localAbierto();
        String token = unirse(local);
        String video = videoGuardado();
        jdbc.update("UPDATE operacion.cancion SET disponibilidad = 'NO_DISPONIBLE' WHERE youtube_video_id = ?", video);

        assertProblema(solicitar(token, video), 409, "SONG_UNAVAILABLE");
        assertThat(contar("SELECT count(*) FROM operacion.solicitud WHERE sesion_id = ?", sesionDe(local))).isZero();
    }

    @Test
    void anActiveRequestWhoseSongBecameUnavailableCannotBeVoted() {
        Local local = localAbierto();
        String video = videoGuardado();
        UUID solicitud = solicitada(unirse(local), video);
        UUID otra = solicitada(unirse(local), videoGuardado());
        jdbc.update("UPDATE operacion.cancion SET disponibilidad = 'NO_DISPONIBLE' WHERE youtube_video_id = ?", video);
        String token = unirse(local);

        assertProblema(votar(token, solicitud), 409, "SONG_UNAVAILABLE");
        assertThat(votos(solicitud)).isEqualTo(1);
        assertThat(votar(token, otra).statusCode()).isEqualTo(200);
        assertThat(votos(otra)).isEqualTo(2);
    }

    @Test
    void aPausedSessionDoesNotAcceptRequestsOrVotes() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        String token = unirse(local);
        UUID solicitud = solicitada(unirse(local), videoGuardado());
        long version = colaVersion(sesion);
        jdbc.update("UPDATE operacion.sesion SET estado = 'PAUSADA' WHERE id = ?", sesion);

        assertProblema(solicitar(token, videoGuardado()), 409, "SESSION_PAUSED");
        assertProblema(votar(token, solicitud), 409, "SESSION_PAUSED");
        assertThat(colaVersion(sesion)).isEqualTo(version);
    }

    @Test
    void aClosingSessionDoesNotAcceptRequests() {
        Local local = localAbierto();
        String token = unirse(local);
        jdbc.update("UPDATE operacion.sesion SET estado = 'CERRANDO' WHERE id = ?", sesionDe(local));

        assertProblema(solicitar(token, videoGuardado()), 409, "SESSION_NOT_ACCEPTING");
    }

    @Test
    void aClosedSessionEndsTheParticipantCredential() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        String token = unirse(local);
        UUID solicitud = solicitada(token, videoGuardado());
        cerrarSesion(sesion);

        assertCredencialRechazada(solicitar(token, videoGuardado()), "SESSION_ENDED");
        assertCredencialRechazada(votar(token, solicitud), "SESSION_ENDED");
        assertCredencialRechazada(colaParticipante(token), "SESSION_ENDED");
    }

    // ---------- voting ----------

    @Test
    void votingAddsOneSupportAndConfirmsIt() {
        Local local = localAbierto();
        UUID solicitud = solicitada(unirse(local), videoGuardado());

        HttpResponse<String> response = votar(unirse(local), solicitud);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body.get("solicitudId").asString()).isEqualTo(solicitud.toString());
        assertThat(body.get("votos").asInt()).isEqualTo(2);
        assertThat(body.get("votado").asBoolean()).isTrue();
        assertThat(body.get("posicion").asInt()).isEqualTo(1);
        assertThat(body.get("creada").asBoolean()).isFalse();
    }

    @Test
    void aParticipantCannotSupportTheSameRequestTwice() {
        Local local = localAbierto();
        UUID solicitud = solicitada(unirse(local), videoGuardado());
        String token = unirse(local);
        assertThat(votar(token, solicitud).statusCode()).isEqualTo(200);

        assertProblema(votar(token, solicitud), 409, "ALREADY_VOTED");
        assertThat(votos(solicitud)).isEqualTo(2);
    }

    @Test
    void theCreatorAlreadyVotedWithTheRequest() {
        Local local = localAbierto();
        String token = unirse(local);
        UUID solicitud = solicitada(token, videoGuardado());

        assertProblema(votar(token, solicitud), 409, "ALREADY_VOTED");
    }

    @Test
    void aRequestOfAnotherSessionOrAnUnknownOneIsNotFound() {
        UUID ajena = solicitada(unirse(localAbierto()), videoGuardado());
        String token = unirse(localAbierto());

        assertProblema(votar(token, ajena), 404, "REQUEST_NOT_FOUND");
        assertProblema(votar(token, UUID.randomUUID()), 404, "REQUEST_NOT_FOUND");
        assertProblema(votar(token, "not-a-uuid"), 400, "INVALID_PARAMETER");
        assertThat(votos(ajena)).isEqualTo(1);
    }

    @Test
    void aFinishedRequestCannotBeVoted() {
        Local local = localAbierto();
        UUID solicitud = solicitada(unirse(local), videoGuardado());
        jdbc.update("UPDATE operacion.solicitud SET estado = 'ATENDIDA', finalizada_at = now() WHERE id = ?", solicitud);

        assertProblema(votar(unirse(local), solicitud), 409, "REQUEST_NOT_ACTIVE");
        assertThat(votos(solicitud)).isEqualTo(1);
    }

    // ---------- re-requesting a song that already played ----------

    @Test
    void aSongThatFinishedLessThan30MinutesAgoCannotBeRequestedAgain() {
        Local local = localAbierto();
        String video = videoGuardado();
        UUID solicitud = solicitada(unirse(local), video);
        jdbc.update("UPDATE operacion.solicitud SET estado = 'ATENDIDA', finalizada_at = now() - interval '29 minutes' "
                + "WHERE id = ?", solicitud);

        assertProblema(solicitar(unirse(local), video), 409, "SONG_RECENTLY_PLAYED");
    }

    @Test
    void after30MinutesTheSongStartsANewCycleForThoseWhoHaveNotSupportedIt() {
        Local local = localAbierto();
        String creador = unirse(local);
        String video = videoGuardado();
        UUID primera = solicitada(creador, video);
        jdbc.update("UPDATE operacion.solicitud SET estado = 'ATENDIDA', finalizada_at = now() - interval '31 minutes' "
                + "WHERE id = ?", primera);

        assertProblema(solicitar(creador, video), 409, "ALREADY_VOTED");
        HttpResponse<String> response = solicitar(unirse(local), video);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        UUID segunda = UUID.fromString(json(response).get("solicitudId").asString());
        assertThat(segunda).isNotEqualTo(primera);
        assertThat(jdbc.queryForObject("SELECT ciclo FROM operacion.solicitud WHERE id = ?", Integer.class, segunda))
                .isEqualTo(2);
        assertProblema(votar(creador, segunda), 409, "ALREADY_VOTED");
    }

    // ---------- events and queue version ----------

    @Test
    void creatingARequestRecordsTheRequestTheFirstVoteAndTheQueueChangeInOneVersion() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        long antes = colaVersion(sesion);

        UUID solicitud = solicitada(unirse(local), videoGuardado());

        assertThat(colaVersion(sesion)).isEqualTo(antes + 1);
        assertThat(jdbc.queryForObject("""
                SELECT (payload ->> 'request_id') || '|' || (payload ->> 'cycle') || '|' || ambito
                FROM integracion.evento_outbox WHERE sesion_id = ? AND event_type = 'request.created'
                """, String.class, sesion)).isEqualTo(solicitud + "|1|B");
        assertThat(jdbc.queryForObject("""
                SELECT (payload ->> 'request_id') || '|' || (payload ->> 'is_initial') || '|' || (payload ->> 'votes_after')
                FROM integracion.evento_outbox WHERE sesion_id = ? AND event_type = 'vote.accepted'
                """, String.class, sesion)).isEqualTo(solicitud + "|true|1");
        assertThat(jdbc.queryForObject("""
                SELECT (payload ->> 'queue_version') || '|' || (payload ->> 'queue_size') || '|' || agregado_version
                FROM integracion.evento_outbox WHERE sesion_id = ? AND event_type = 'queue.changed'
                """, String.class, sesion)).isEqualTo((antes + 1) + "|1|" + (antes + 1));
        assertThat(contar("SELECT count(DISTINCT correlacion_id) FROM integracion.evento_outbox WHERE sesion_id = ? "
                + "AND event_type IN ('request.created', 'vote.accepted', 'queue.changed')", sesion)).isEqualTo(1);
    }

    @Test
    void aVoteRecordsItsEventAndBumpsTheQueueVersion() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        UUID solicitud = solicitada(unirse(local), videoGuardado());
        long antes = colaVersion(sesion);

        votar(unirse(local), solicitud);

        assertThat(colaVersion(sesion)).isEqualTo(antes + 1);
        assertThat(eventos(sesion, "vote.accepted")).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT (payload ->> 'votes_after') FROM integracion.evento_outbox
                WHERE sesion_id = ? AND event_type = 'vote.accepted' AND payload ->> 'is_initial' = 'false'
                """, String.class, sesion)).isEqualTo("2");
        assertThat(eventos(sesion, "queue.changed")).isEqualTo(2);
    }

    @Test
    void aRejectedActionChangesNothing() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        String token = unirse(local);
        UUID solicitud = solicitada(token, videoGuardado());
        long antes = colaVersion(sesion);

        votar(token, solicitud);

        assertThat(colaVersion(sesion)).isEqualTo(antes);
        assertThat(eventos(sesion, "queue.changed")).isEqualTo(1);
    }

    @Test
    void closingTheSessionWithPendingRequestsBumpsTheQueueVersion() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        solicitada(unirse(local), videoGuardado());
        long antes = colaVersion(sesion);

        cerrarSesion(sesion);

        assertThat(colaVersion(sesion)).isEqualTo(antes + 1);
        assertThat(jdbc.queryForObject("""
                SELECT (payload ->> 'reason_code') || '|' || (payload ->> 'queue_size') FROM integracion.evento_outbox
                WHERE sesion_id = ? AND event_type = 'queue.changed' AND agregado_version = ?
                """, String.class, sesion, antes + 1)).isEqualTo("session.closed|0");
    }

    // ---------- credentials ----------

    @Test
    void anonymousCallersAndStaffTokensCannotRequestOrVote() {
        Local local = localAbierto();
        UUID solicitud = solicitada(unirse(local), videoGuardado());
        String video = videoGuardado();

        assertThat(solicitar(null, video).statusCode()).isEqualTo(401);
        assertThat(votar(null, solicitud).statusCode()).isEqualTo(401);
        assertThat(colaParticipante(null).statusCode()).isEqualTo(401);
        assertCredencialRechazada(solicitar(tokenSinEstablecimiento(), video), "INVALID_CREDENTIAL");
        assertCredencialRechazada(votar(tokenSinEstablecimiento(), solicitud), "INVALID_CREDENTIAL");
        assertCredencialRechazada(colaParticipante(tokenSinEstablecimiento()), "INVALID_CREDENTIAL");
        assertThat(votos(solicitud)).isEqualTo(1);
    }
}
