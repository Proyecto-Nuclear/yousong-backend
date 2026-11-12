package cue.edu.co.yousongbackend.establecimiento;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.cola.ColaApiTestSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * System pause and maintenance of the caller's establishment. Both stop joins and new sessions; the pause also
 * stops requests and votes of participants already inside; maintenance needs no session in operation and cleans
 * leftovers without deleting any evidence.
 */
class EstadoOperativoApiTest extends ColaApiTestSupport {

    private static final String MI = "/api/mi-establecimiento";
    private static final String PAUSA = MI + "/pausa";
    private static final String REANUDACION = MI + "/reanudacion";
    private static final String MANTENIMIENTO = MI + "/mantenimiento/inicio";
    private static final String FIN_MANTENIMIENTO = MI + "/mantenimiento/fin";

    private Map<String, Object> banderas(Local local) {
        return jdbc.queryForMap("SELECT pausado, en_mantenimiento, activo FROM operacion.establecimiento WHERE id = ?",
                local.id());
    }

    private List<String> auditoria(Local local) {
        return jdbc.queryForList("SELECT accion_codigo || ':' || entidad_tipo || ':' || entidad_ref || ':' || resultado "
                + "FROM operacion.auditoria WHERE establecimiento_id = ? ORDER BY registrada_at", String.class, local.id());
    }

    private List<String> camposCambiados(Local local) {
        return jdbc.queryForList("SELECT payload->>'field_codes' FROM integracion.evento_outbox "
                + "WHERE event_type = 'configuration.changed' AND establecimiento_id = ? ORDER BY agregado_version",
                String.class, local.id());
    }

    // ---------- system pause ----------

    @Test
    void theOwnerPausesTheEstablishmentAndNothingNewStarts() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        String token = unirse(local);
        UUID solicitud = solicitada(unirse(local), videoGuardado());

        HttpResponse<String> pausado = post(PAUSA, local.token());

        assertThat(pausado.statusCode()).as(pausado.body()).isEqualTo(200);
        assertThat(json(pausado).get("pausado").asBoolean()).isTrue();
        assertThat(json(get(MI, local.token())).get("pausado").asBoolean()).isTrue();
        JsonNode estado = json(get(estado(local.slug()), null));
        assertThat(estado.get("servicio").asString()).isEqualTo("PAUSADO");
        assertThat(estado.get("puedeParticipar").asBoolean()).isFalse();
        assertProblema(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false)), 409, "ESTABLISHMENT_PAUSED");
        assertProblema(solicitar(token, videoGuardado()), 409, "ESTABLISHMENT_PAUSED");
        assertProblema(votar(token, solicitud), 409, "ESTABLISHMENT_PAUSED");
        assertThat(colaParticipante(token).statusCode()).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT estado FROM operacion.sesion WHERE id = ?", String.class, sesion))
                .isEqualTo("ABIERTA"); // independent of the session pause
        assertProblema(post(PAUSA, local.token()), 409, "ESTABLISHMENT_ALREADY_PAUSED");

        cerrarSesion(local);
        assertProblema(post(MI + "/sesiones", local.token()), 409, "ESTABLISHMENT_PAUSED");
    }

    @Test
    void resumingRestoresTheServiceWithEventsAndAudit() {
        Local local = localListoParaAbrir();
        post(PAUSA, local.token());

        HttpResponse<String> reanudado = post(REANUDACION, local.token());

        assertThat(reanudado.statusCode()).as(reanudado.body()).isEqualTo(200);
        assertThat(json(reanudado).get("pausado").asBoolean()).isFalse();
        assertProblema(post(REANUDACION, local.token()), 409, "ESTABLISHMENT_NOT_PAUSED");
        abrirSesion(local);
        assertThat(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false)).statusCode()).isEqualTo(201);
        assertThat(camposCambiados(local)).containsExactly("[\"pausado\"]", "[\"pausado\"]");
        assertThat(auditoria(local)).containsExactly("ESTABLECIMIENTO_PAUSADO:ESTABLECIMIENTO:" + local.id() + ":EXITO",
                "ESTABLECIMIENTO_REANUDADO:ESTABLECIMIENTO:" + local.id() + ":EXITO");
    }

    // ---------- maintenance ----------

    @Test
    void maintenanceNeedsNoSessionInOperation() {
        Local local = localAbierto();

        assertProblema(post(MANTENIMIENTO, local.token()), 409, "SESSION_IN_PROGRESS");

        assertThat(banderas(local)).containsEntry("en_mantenimiento", false);
        assertThat(auditoria(local)).isEmpty();
    }

    @Test
    void maintenanceFinalizesLeftoversWithoutDeletingEvidenceAndAnnouncesIt() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        solicitada(unirse(local), videoGuardado());
        cerrarSesion(local);
        UUID participante = participacion(sesion);
        UUID sobrante = solicitud(sesion, cancion(), participante, "PENDIENTE", null);
        int solicitudes = contar("SELECT count(*) FROM operacion.solicitud WHERE sesion_id = ?", sesion);
        int votos = contar("SELECT count(*) FROM operacion.voto WHERE sesion_id = ?", sesion);
        int participaciones = contar("SELECT count(*) FROM operacion.participacion WHERE sesion_id = ?", sesion);

        HttpResponse<String> response = post(MANTENIMIENTO, local.token());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response).get("solicitudesFinalizadas").asInt()).isEqualTo(1);
        assertThat(json(response).get("establecimiento").get("enMantenimiento").asBoolean()).isTrue();
        assertThat(jdbc.queryForMap("SELECT estado, finalizada_at IS NOT NULL AS finalizada FROM operacion.solicitud "
                + "WHERE id = ?", sobrante)).containsEntry("estado", "NO_ATENDIDA_CIERRE").containsEntry("finalizada", true);
        assertThat(contar("SELECT count(*) FROM operacion.solicitud WHERE sesion_id = ?", sesion)).isEqualTo(solicitudes);
        assertThat(contar("SELECT count(*) FROM operacion.voto WHERE sesion_id = ?", sesion)).isEqualTo(votos);
        assertThat(contar("SELECT count(*) FROM operacion.participacion WHERE sesion_id = ?", sesion))
                .isEqualTo(participaciones);
        assertThat(jdbc.queryForMap("SELECT payload->>'session_ref' AS ref, payload->>'projection_reset_count' AS conteo, "
                + "payload->>'result' AS resultado FROM integracion.evento_outbox WHERE event_type = 'maintenance.executed' "
                + "AND establecimiento_id = ?", local.id()))
                .containsEntry("ref", sesion.toString()).containsEntry("conteo", "1").containsEntry("resultado", "OK");
        assertThat(auditoria(local)).containsExactly("MANTENIMIENTO_INICIADO:ESTABLECIMIENTO:" + local.id() + ":EXITO");
    }

    @Test
    void duringMaintenanceTheQrSaysSoAndNothingOpens() {
        Local local = localListoParaAbrir();

        HttpResponse<String> response = post(MANTENIMIENTO, local.token());

        assertThat(json(response).get("solicitudesFinalizadas").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT payload->>'session_ref' FROM integracion.evento_outbox "
                + "WHERE event_type = 'maintenance.executed' AND establecimiento_id = ?", String.class, local.id())).isNull();
        JsonNode estado = json(get(estado(local.slug()), null));
        assertThat(estado.get("servicio").asString()).isEqualTo("MANTENIMIENTO");
        assertThat(estado.get("puedeParticipar").asBoolean()).isFalse();
        assertProblema(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false)), 409,
                "ESTABLISHMENT_IN_MAINTENANCE");
        assertProblema(post(MI + "/sesiones", local.token()), 409, "ESTABLISHMENT_IN_MAINTENANCE");
        assertProblema(post(MANTENIMIENTO, local.token()), 409, "ALREADY_IN_MAINTENANCE");
        assertThat(json(get(MI, local.token())).get("enMantenimiento").asBoolean()).isTrue();

        HttpResponse<String> fin = post(FIN_MANTENIMIENTO, local.token());

        assertThat(fin.statusCode()).as(fin.body()).isEqualTo(200);
        assertThat(json(fin).get("enMantenimiento").asBoolean()).isFalse();
        assertProblema(post(FIN_MANTENIMIENTO, local.token()), 409, "NOT_IN_MAINTENANCE");
        abrirSesion(local);
        assertThat(auditoria(local)).containsExactly("MANTENIMIENTO_INICIADO:ESTABLECIMIENTO:" + local.id() + ":EXITO",
                "MANTENIMIENTO_FINALIZADO:ESTABLECIMIENTO:" + local.id() + ":EXITO");
    }

    // ---------- ownership ----------

    @Test
    void anOwnerOnlyEverPausesOrMaintainsTheirOwnEstablishment() {
        Local ana = localListoParaAbrir();
        Local luis = localListoParaAbrir();
        Map<String, Object> deAna = banderas(ana);

        assertThat(post(PAUSA + "?establecimientoId=" + ana.id(), luis.token()).statusCode()).isEqualTo(200);
        assertThat(post(REANUDACION, luis.token()).statusCode()).isEqualTo(200);
        assertThat(post(MANTENIMIENTO + "?establecimientoId=" + ana.id(), luis.token()).statusCode()).isEqualTo(200);

        assertThat(banderas(ana)).isEqualTo(deAna);
        assertThat(auditoria(ana)).isEmpty();
        assertThat(banderas(luis)).containsEntry("en_mantenimiento", true);
        for (String ruta : List.of(PAUSA, REANUDACION, MANTENIMIENTO, FIN_MANTENIMIENTO)) {
            assertProblema(post(ruta, tokenSinEstablecimiento()), 404, "NO_ESTABLISHMENT");
            assertThat(post(ruta, null).statusCode()).isEqualTo(401);
        }
    }
}
