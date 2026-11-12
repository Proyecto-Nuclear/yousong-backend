package cue.edu.co.yousongbackend.sesion;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.cola.ClienteSse;
import cue.edu.co.yousongbackend.cola.ColaApiTestSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The owner pauses and resumes the session in operation. While it is paused nobody joins, requests or votes
 * (409 SESSION_PAUSED); participants keep their credentials and keep reading the queue and its stream.
 */
class PausaSesionApiTest extends ColaApiTestSupport {

    private static final String PAUSA = "/api/mi-establecimiento/sesion/pausa";
    private static final String REANUDACION = "/api/mi-establecimiento/sesion/reanudacion";

    private String estadoSesion(UUID sesion) {
        return jdbc.queryForObject("SELECT estado FROM operacion.sesion WHERE id = ?", String.class, sesion);
    }

    private List<String> cambiosDeEstado(UUID sesion) {
        return jdbc.queryForList("SELECT (payload->>'session_id') || ':' || (payload->>'from_state') || '>' "
                + "|| (payload->>'to_state') || ':' || (payload->>'reason_code') FROM integracion.evento_outbox "
                + "WHERE event_type = 'session.state_changed' AND sesion_id = ? ORDER BY agregado_version", String.class, sesion);
    }

    private List<String> auditoria(Local local) {
        return jdbc.queryForList("SELECT accion_codigo || ':' || entidad_tipo || ':' || entidad_ref || ':' || resultado "
                + "FROM operacion.auditoria WHERE establecimiento_id = ? ORDER BY registrada_at", String.class, local.id());
    }

    @Test
    void theOwnerPausesAndResumesTheSessionWithEventsAndAudit() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);

        HttpResponse<String> pausada = post(PAUSA, local.token());

        assertThat(pausada.statusCode()).as(pausada.body()).isEqualTo(200);
        assertThat(json(pausada).get("estado").asString()).isEqualTo("PAUSADA");
        assertThat(estadoSesion(sesion)).isEqualTo("PAUSADA");
        assertThat(json(get(estado(local.slug()), null)).get("servicio").asString()).isEqualTo("PAUSADO");

        HttpResponse<String> reanudada = post(REANUDACION, local.token());

        assertThat(reanudada.statusCode()).as(reanudada.body()).isEqualTo(200);
        assertThat(json(reanudada).get("estado").asString()).isEqualTo("ABIERTA");
        assertThat(estadoSesion(sesion)).isEqualTo("ABIERTA");
        assertThat(cambiosDeEstado(sesion)).containsExactly(
                sesion + ":ABIERTA>PAUSADA:PAUSA_MANUAL", sesion + ":PAUSADA>ABIERTA:REANUDACION_MANUAL");
        assertThat(auditoria(local)).containsExactly("SESION_PAUSADA:SESION:" + sesion + ":EXITO",
                "SESION_REANUDADA:SESION:" + sesion + ":EXITO");
    }

    @Test
    void wrongStatesAreConflictsAndChangeNothing() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);

        assertProblema(post(REANUDACION, local.token()), 409, "SESSION_NOT_PAUSED");
        post(PAUSA, local.token());
        assertProblema(post(PAUSA, local.token()), 409, "SESSION_NOT_PAUSABLE");
        jdbc.update("UPDATE operacion.sesion SET estado = 'CERRANDO' WHERE id = ?", sesion);
        assertProblema(post(PAUSA, local.token()), 409, "SESSION_NOT_PAUSABLE");
        assertProblema(post(REANUDACION, local.token()), 409, "SESSION_NOT_PAUSED");

        assertThat(cambiosDeEstado(sesion)).hasSize(1);
        Local sinSesion = localListoParaAbrir();
        assertProblema(post(PAUSA, sinSesion.token()), 404, "NO_ACTIVE_SESSION");
        assertProblema(post(REANUDACION, sinSesion.token()), 404, "NO_ACTIVE_SESSION");
        assertProblema(post(PAUSA, tokenSinEstablecimiento()), 404, "NO_ESTABLISHMENT");
        assertThat(post(PAUSA, null).statusCode()).isEqualTo(401);
    }

    @Test
    void whilePausedParticipantsKeepReadingButCannotJoinRequestOrVote() {
        Local local = localAbierto();
        String token = unirse(local);
        UUID solicitud = solicitada(unirse(local), videoGuardado());
        post(PAUSA, local.token());

        assertProblema(solicitar(token, videoGuardado()), 409, "SESSION_PAUSED");
        assertProblema(solicitar(token, videoIdAleatorio()), 409, "SESSION_PAUSED");
        assertProblema(votar(token, solicitud), 409, "SESSION_PAUSED");
        assertProblema(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false)), 409, "SESSION_PAUSED");
        HttpResponse<String> cola = colaParticipante(token);
        assertThat(cola.statusCode()).as(cola.body()).isEqualTo(200);
        assertThat(json(cola).get("estado").asString()).isEqualTo("PAUSADO");
        assertThat(get(MI_SESION, token).statusCode()).isEqualTo(200);

        post(REANUDACION, local.token());
        assertThat(votar(token, solicitud).statusCode()).isEqualTo(200);
    }

    @Test
    void pausingAndResumingRefreshTheLiveQueue() {
        Local local = localAbierto();

        try (ClienteSse cliente = ClienteSse.abrir(port, "/api/public/establecimientos/" + local.slug() + "/cola/eventos")) {
            assertThat(cliente.siguiente().json().get("estado").asString()).isEqualTo("ABIERTO");
            post(PAUSA, local.token());
            JsonNode pausada = cliente.siguiente().json();
            assertThat(pausada.get("estado").asString()).isEqualTo("PAUSADO");
            post(REANUDACION, local.token());
            assertThat(cliente.siguiente().json().get("estado").asString()).isEqualTo("ABIERTO");
        }
    }

    @Test
    void anOwnerOnlyPausesTheirOwnSession() {
        Local ana = localAbierto();
        Local luis = localAbierto();
        UUID sesionAna = sesionDe(ana);

        assertThat(post(PAUSA + "?sesionId=" + sesionAna, luis.token()).statusCode()).isEqualTo(200);

        assertThat(estadoSesion(sesionAna)).isEqualTo("ABIERTA");
        assertThat(estadoSesion(sesionDe(luis))).isEqualTo("PAUSADA");
        assertThat(cambiosDeEstado(sesionAna)).isEmpty();
    }
}
