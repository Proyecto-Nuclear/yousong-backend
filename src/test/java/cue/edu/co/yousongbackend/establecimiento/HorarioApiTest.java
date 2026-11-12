package cue.edu.co.yousongbackend.establecimiento;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.participante.ParticipanteApiTestSupport;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/** The owner's weekly windows: read, replaced as a whole, validated, announced and audited. */
class HorarioApiTest extends ParticipanteApiTestSupport {

    static final String HORARIOS = "/api/mi-establecimiento/horarios";

    private List<Map<String, Object>> filas(Local local) {
        return jdbc.queryForList("SELECT dia_semana, inicio::text AS inicio, fin::text AS fin, cruza_medianoche, activo "
                + "FROM operacion.horario WHERE establecimiento_id = ? ORDER BY dia_semana, inicio", local.id());
    }

    @Test
    void anEstablishmentHasNoWindowsUntilTheOwnerSetsThem() {
        Local local = establecimiento();

        HttpResponse<String> response = get(HORARIOS, local.token());

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json(response)).isEmpty();
    }

    @Test
    void theOwnerReplacesTheWeeklySetAndTheMidnightCrossingIsDerived() {
        Local local = establecimiento();

        HttpResponse<String> response = put(HORARIOS, local.token(), """
                [{"diaSemana":5,"inicio":"22:00","fin":"02:00"},{"diaSemana":1,"inicio":"18:00","fin":"23:30"}]""");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        JsonNode body = json(response);
        assertThat(body).hasSize(2);
        assertThat(body.get(0).get("diaSemana").asInt()).isEqualTo(1);
        assertThat(body.get(0).get("inicio").asString()).isEqualTo("18:00");
        assertThat(body.get(0).get("fin").asString()).isEqualTo("23:30");
        assertThat(body.get(0).get("cruzaMedianoche").asBoolean()).isFalse();
        assertThat(body.get(1).get("diaSemana").asInt()).isEqualTo(5);
        assertThat(body.get(1).get("cruzaMedianoche").asBoolean()).isTrue();
        assertThat(json(get(HORARIOS, local.token()))).isEqualTo(body);
        assertThat(filas(local)).containsExactly(
                Map.of("dia_semana", 1, "inicio", "18:00:00", "fin", "23:30:00", "cruza_medianoche", false,
                        "activo", true),
                Map.of("dia_semana", 5, "inicio", "22:00:00", "fin", "02:00:00", "cruza_medianoche", true,
                        "activo", true));

        assertThat(put(HORARIOS, local.token(), "[{\"diaSemana\":3,\"inicio\":\"20:00\",\"fin\":\"23:00\"}]")
                .statusCode()).isEqualTo(200);
        assertThat(filas(local)).hasSize(1);
        assertThat(put(HORARIOS, local.token(), "[]").statusCode()).isEqualTo(200);
        assertThat(filas(local)).isEmpty();
    }

    @Test
    void replacingAnnouncesAConfigurationChangeAndIsAudited() {
        Local local = establecimiento();

        put(HORARIOS, local.token(), "[{\"diaSemana\":1,\"inicio\":\"20:00\",\"fin\":\"23:00\"}]");

        assertThat(jdbc.queryForList("SELECT payload->>'field_codes' FROM integracion.evento_outbox "
                + "WHERE event_type = 'configuration.changed' AND establecimiento_id = ?", String.class, local.id()))
                .containsExactly("[\"horario\"]");
        assertThat(jdbc.queryForList("SELECT accion_codigo || ':' || entidad_tipo || ':' || entidad_ref || ':' || resultado "
                + "FROM operacion.auditoria WHERE establecimiento_id = ?", String.class, local.id()))
                .containsExactly("HORARIO_REEMPLAZADO:ESTABLECIMIENTO:" + local.id() + ":EXITO");
    }

    @Test
    void overlappingWindowsAreRejectedAndNothingChanges() {
        Local local = establecimiento();
        put(HORARIOS, local.token(), "[{\"diaSemana\":2,\"inicio\":\"20:00\",\"fin\":\"23:00\"}]");
        List<Map<String, Object>> antes = filas(local);

        for (String cuerpo : List.of(
                "[{\"diaSemana\":1,\"inicio\":\"20:00\",\"fin\":\"23:00\"},{\"diaSemana\":1,\"inicio\":\"22:00\",\"fin\":\"23:30\"}]",
                "[{\"diaSemana\":1,\"inicio\":\"22:00\",\"fin\":\"02:00\"},{\"diaSemana\":2,\"inicio\":\"01:00\",\"fin\":\"03:00\"}]",
                "[{\"diaSemana\":7,\"inicio\":\"23:00\",\"fin\":\"02:00\"},{\"diaSemana\":1,\"inicio\":\"01:00\",\"fin\":\"03:00\"}]",
                "[{\"diaSemana\":4,\"inicio\":\"20:00\",\"fin\":\"23:00\"},{\"diaSemana\":4,\"inicio\":\"20:00\",\"fin\":\"23:00\"}]")) {
            assertProblema(put(HORARIOS, local.token(), cuerpo), 400, "SCHEDULE_OVERLAP");
        }
        assertThat(put(HORARIOS, local.token(), "[{\"diaSemana\":1,\"inicio\":\"20:00\",\"fin\":\"22:00\"},"
                + "{\"diaSemana\":1,\"inicio\":\"22:00\",\"fin\":\"00:00\"},{\"diaSemana\":2,\"inicio\":\"00:00\",\"fin\":\"01:00\"}]")
                .statusCode()).isEqualTo(200);
        assertThat(filas(local)).hasSize(3).isNotEqualTo(antes);
    }

    @Test
    void malformedWindowsAreRejectedAndNothingChanges() {
        Local local = establecimiento();
        put(HORARIOS, local.token(), "[{\"diaSemana\":2,\"inicio\":\"20:00\",\"fin\":\"23:00\"}]");
        List<Map<String, Object>> antes = filas(local);

        for (String cuerpo : List.of(
                "[{\"diaSemana\":0,\"inicio\":\"20:00\",\"fin\":\"23:00\"}]",
                "[{\"diaSemana\":8,\"inicio\":\"20:00\",\"fin\":\"23:00\"}]",
                "[{\"inicio\":\"20:00\",\"fin\":\"23:00\"}]",
                "[{\"diaSemana\":1,\"inicio\":\"25:00\",\"fin\":\"23:00\"}]",
                "[{\"diaSemana\":1,\"inicio\":\"7:05\",\"fin\":\"23:00\"}]",
                "[{\"diaSemana\":1,\"inicio\":\"20:00:00\",\"fin\":\"23:00\"}]",
                "[{\"diaSemana\":1,\"inicio\":\"20:00\"}]",
                "[{\"diaSemana\":1,\"inicio\":\"20:00\",\"fin\":\"20:00\"}]",
                "[null]")) {
            assertProblema(put(HORARIOS, local.token(), cuerpo), 400, "INVALID_SCHEDULE");
        }
        assertThat(put(HORARIOS, local.token(), "{\"diaSemana\":1}").statusCode()).isEqualTo(400);
        assertThat(put(HORARIOS, local.token(), "not json").statusCode()).isEqualTo(400);
        assertThat(filas(local)).isEqualTo(antes);
    }

    @Test
    void atMostTwentyOneWindowsAreAccepted() {
        Local local = establecimiento();

        assertThat(put(HORARIOS, local.token(), semana(21)).statusCode()).isEqualTo(200);
        assertProblema(put(HORARIOS, local.token(), semana(22)), 400, "TOO_MANY_WINDOWS");
        assertThat(filas(local)).hasSize(21);
    }

    /** {@code n} disjoint one-hour windows spread over the week. */
    private static String semana(int n) {
        StringJoiner json = new StringJoiner(",", "[", "]");
        for (int i = 0; i < n; i++) {
            json.add("{\"diaSemana\":" + (i % 7 + 1) + ",\"inicio\":\"" + String.format("%02d", 10 + i / 7)
                    + ":00\",\"fin\":\"" + String.format("%02d", 10 + i / 7) + ":30\"}");
        }
        return json.toString();
    }

    @Test
    void anOwnerOnlyEverReadsAndReplacesTheirOwnWindows() {
        Local ana = establecimiento();
        Local luis = establecimiento();
        put(HORARIOS, ana.token(), "[{\"diaSemana\":2,\"inicio\":\"20:00\",\"fin\":\"23:00\"}]");
        List<Map<String, Object>> deAna = filas(ana);

        assertThat(json(get(HORARIOS + "?establecimientoId=" + ana.id(), luis.token()))).isEmpty();
        assertThat(put(HORARIOS + "?establecimientoId=" + ana.id(), luis.token(), "[]").statusCode()).isEqualTo(200);
        assertThat(put(HORARIOS, luis.token(), "[{\"diaSemana\":6,\"inicio\":\"21:00\",\"fin\":\"23:00\"}]")
                .statusCode()).isEqualTo(200);

        assertThat(filas(ana)).isEqualTo(deAna);
        assertThat(filas(luis)).hasSize(1);
        assertProblema(get(HORARIOS, tokenSinEstablecimiento()), 404, "NO_ESTABLISHMENT");
        assertProblema(put(HORARIOS, tokenSinEstablecimiento(), "[]"), 404, "NO_ESTABLISHMENT");
        assertThat(get(HORARIOS, null).statusCode()).isEqualTo(401);
        assertThat(put(HORARIOS, null, "[]").statusCode()).isEqualTo(401);
    }
}
