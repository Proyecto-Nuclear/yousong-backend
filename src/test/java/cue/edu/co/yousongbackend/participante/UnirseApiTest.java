package cue.edu.co.yousongbackend.participante;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** Joining the open session of an establishment after accepting the privacy policy. */
class UnirseApiTest extends ParticipanteApiTestSupport {

    private int participacionesDe(UUID sesionId) {
        return contar("SELECT count(*) FROM operacion.participacion WHERE sesion_id = ?", sesionId);
    }

    @Test
    void joiningCreatesTheDeviceTheParticipationTheConsentsAndAHashedCredential() {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);
        Instant antes = Instant.now();

        HttpResponse<String> response = unirse(local.slug(), cuerpo("ANDROID", POLICY_VERSION, true, true));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(
                value -> assertThat(value).contains("no-store"));
        JsonNode body = json(response);
        String token = body.get("token").asString();
        assertThat(token).matches("pt_[A-Za-z0-9_-]{43}");
        assertThat(body.get("seudonimo").asString()).matches("p-[0-9a-f]{6}");
        assertThat(body.get("servicio").asString()).isEqualTo("ABIERTO");
        assertThat(body.get("establecimiento").get("nombre").asString()).isEqualTo("Local de prueba");
        assertThat(body.get("establecimiento").propertyNames()).containsExactly("nombre");
        Instant expiraAt = Instant.parse(body.get("expiraAt").asString());
        assertThat(Duration.between(antes, expiraAt)).isBetween(Duration.ofHours(23), Duration.ofHours(25));

        Map<String, Object> participacion = jdbc.queryForMap(
                "SELECT id, dispositivo_id, seudonimo FROM operacion.participacion WHERE sesion_id = ?", sesionId);
        assertThat(participacion.get("seudonimo")).isEqualTo(body.get("seudonimo").asString());
        UUID dispositivoId = (UUID) participacion.get("dispositivo_id");
        UUID participacionId = (UUID) participacion.get("id");

        Map<String, Object> dispositivo = jdbc.queryForMap(
                "SELECT canal, expira_at, revocado_at FROM operacion.dispositivo WHERE id = ?", dispositivoId);
        assertThat(dispositivo.get("canal")).isEqualTo("ANDROID");
        assertThat(dispositivo.get("revocado_at")).isNull();
        Instant dispositivoExpira = ((java.sql.Timestamp) dispositivo.get("expira_at")).toInstant();
        assertThat(Duration.between(antes, dispositivoExpira)).isBetween(Duration.ofDays(29), Duration.ofDays(31));

        List<Map<String, Object>> consentimientos = jdbc.queryForList("SELECT finalidad, accion, politica_version, canal "
                + "FROM operacion.consentimiento WHERE participacion_id = ? ORDER BY finalidad", participacionId);
        assertThat(consentimientos).hasSize(2);
        assertThat(consentimientos.get(0)).containsEntry("finalidad", "F1").containsEntry("accion", "AUTORIZA")
                .containsEntry("politica_version", POLICY_VERSION).containsEntry("canal", "ANDROID");
        assertThat(consentimientos.get(1)).containsEntry("finalidad", "F2").containsEntry("accion", "AUTORIZA");

        Map<String, Object> credencial = jdbc.queryForMap("SELECT refresh_hash, personal_id, expira_at, revocada_at "
                + "FROM operacion.sesion_acceso WHERE dispositivo_id = ?", dispositivoId);
        String hash = (String) credencial.get("refresh_hash");
        assertThat(hash).matches("[0-9a-f]{64}").isNotEqualTo(token);
        assertThat(credencial.get("personal_id")).isNull();
        assertThat(credencial.get("revocada_at")).isNull();
        assertThat(((java.sql.Timestamp) credencial.get("expira_at")).toInstant()).isEqualTo(expiraAt);
    }

    @Test
    void theTokenIsNeverStoredInClear() {
        Local local = localAbierto();
        String token = unirse(local);

        // Every text-like column of every application table: the token must not appear anywhere.
        List<Map<String, Object>> columnas = jdbc.queryForList("SELECT c.table_schema, c.table_name, c.column_name "
                + "FROM information_schema.columns c JOIN information_schema.tables t "
                + "ON t.table_schema = c.table_schema AND t.table_name = c.table_name AND t.table_type = 'BASE TABLE' "
                + "WHERE c.table_schema NOT IN ('pg_catalog', 'information_schema') "
                + "AND c.data_type IN ('character varying', 'character', 'text', 'jsonb', 'json')");
        assertThat(columnas).isNotEmpty();
        for (Map<String, Object> columna : columnas) {
            String sql = "SELECT count(*) FROM \"" + columna.get("table_schema") + "\".\"" + columna.get("table_name")
                    + "\" WHERE \"" + columna.get("column_name") + "\"::text LIKE ?";
            assertThat(contar(sql, "%" + token.substring(3) + "%"))
                    .as("%s.%s.%s", columna.get("table_schema"), columna.get("table_name"), columna.get("column_name"))
                    .isZero();
        }
    }

    @Test
    void withoutTheOptionalPurposeOnlyTheRequiredConsentIsRecorded() {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);

        HttpResponse<String> sinF2 = unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, null));
        HttpResponse<String> conF2Falso = unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false));

        assertThat(sinF2.statusCode()).as(sinF2.body()).isEqualTo(201);
        assertThat(conF2Falso.statusCode()).as(conF2Falso.body()).isEqualTo(201);
        assertThat(contar("SELECT count(*) FROM operacion.consentimiento c JOIN operacion.participacion p "
                + "ON p.id = c.participacion_id WHERE p.sesion_id = ? AND c.finalidad = 'F1'", sesionId)).isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM operacion.consentimiento c JOIN operacion.participacion p "
                + "ON p.id = c.participacion_id WHERE p.sesion_id = ? AND c.finalidad = 'F2'", sesionId)).isZero();
        assertThat(json(sinF2).get("seudonimo").asString()).isNotEqualTo(json(conF2Falso).get("seudonimo").asString());
    }

    @Test
    void joiningWithoutTheRequiredConsentIsRejectedAndCreatesNothing() {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);
        int dispositivos = contar("SELECT count(*) FROM operacion.dispositivo");
        int credenciales = contar("SELECT count(*) FROM operacion.sesion_acceso");

        assertProblema(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, false, true)), 400, "CONSENT_REQUIRED");
        assertProblema(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, null, true)), 400, "CONSENT_REQUIRED");

        assertThat(participacionesDe(sesionId)).isZero();
        assertThat(contar("SELECT count(*) FROM operacion.dispositivo")).isEqualTo(dispositivos);
        assertThat(contar("SELECT count(*) FROM operacion.sesion_acceso")).isEqualTo(credenciales);
    }

    @Test
    void anOutdatedPolicyVersionIsAConflictThatNamesTheCurrentOne() {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);

        HttpResponse<String> response = unirse(local.slug(), cuerpo("WEB", "0.9", true, false));

        assertProblema(response, 409, "POLICY_VERSION_MISMATCH");
        assertThat(json(response).get("detail").asString()).contains(POLICY_VERSION);
        assertThat(participacionesDe(sesionId)).isZero();
    }

    @Test
    void anInvalidChannelIsABadRequest() {
        Local local = localAbierto();

        HttpResponse<String> response = unirse(local.slug(), cuerpo("IOS", POLICY_VERSION, true, false));

        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
    }

    @Test
    void withoutASessionThereIsNothingToJoin() {
        Local local = localListoParaAbrir();

        assertProblema(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false)), 409, "NO_ACTIVE_SESSION");
    }

    @Test
    void aPausedSessionCannotBeJoined() {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);
        jdbc.update("UPDATE operacion.sesion SET estado = 'PAUSADA' WHERE id = ?", sesionId);

        assertProblema(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false)), 409, "SESSION_PAUSED");
        assertThat(participacionesDe(sesionId)).isZero();
    }

    @Test
    void anUnknownEstablishmentIsNotFound() {
        assertProblema(unirse("no-such-place", cuerpo("WEB", POLICY_VERSION, true, false)), 404, "ESTABLISHMENT_NOT_FOUND");
    }

    @Test
    void anInactiveEstablishmentCannotBeJoined() {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);
        jdbc.update("UPDATE operacion.establecimiento SET activo = false WHERE id = ?", local.id());

        assertProblema(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false)), 409, "ESTABLISHMENT_INACTIVE");
        assertThat(participacionesDe(sesionId)).isZero();
    }
}
