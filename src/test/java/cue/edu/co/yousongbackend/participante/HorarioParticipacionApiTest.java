package cue.edu.co.yousongbackend.participante;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.RelojDePrueba;
import cue.edu.co.yousongbackend.support.RelojDePrueba.RelojMovible;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

/**
 * With weekly windows the QR only works inside them: outside, the public status says CERRADO with the next
 * opening and joining is refused, even when the owner opened a session. Without windows nothing changes.
 */
@Import(RelojDePrueba.class)
class HorarioParticipacionApiTest extends ParticipanteApiTestSupport {

    private static final String HORARIOS = "/api/mi-establecimiento/horarios";
    /** Monday 2026-11-09 20:00-23:00 in Bogota (UTC-5): 2026-11-10 01:00Z to 04:00Z. */
    private static final String LUNES_NOCHE = "[{\"diaSemana\":1,\"inicio\":\"20:00\",\"fin\":\"23:00\"}]";
    private static final Instant DENTRO = Instant.parse("2026-11-10T02:00:00Z");
    private static final Instant FUERA = Instant.parse("2026-11-09T21:00:00Z"); // Monday 16:00 in Bogota

    @Autowired
    private RelojMovible reloj;

    private Local conHorario(String zona) {
        Local local = establecimiento(zona, true);
        listaDefecto(local.id());
        assertThat(put(HORARIOS, local.token(), LUNES_NOCHE).statusCode()).isEqualTo(200);
        return local;
    }

    @Test
    void insideAWindowTheQrWorksAsUsual() {
        reloj.fijar(DENTRO);
        Local local = conHorario("America/Bogota");
        abrirSesion(local);

        JsonNode estado = json(get(estado(local.slug()), null));

        assertThat(estado.get("servicio").asString()).isEqualTo("ABIERTO");
        assertThat(estado.get("puedeParticipar").asBoolean()).isTrue();
        assertThat(estado.has("proximaApertura")).isFalse();
        assertThat(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false)).statusCode()).isEqualTo(201);
    }

    @Test
    void outsideEveryWindowTheStatusIsClosedWithTheNextOpeningEvenWithAnOpenSession() {
        reloj.fijar(FUERA);
        Local local = conHorario("America/Bogota");
        UUID sesion = abrirSesion(local); // the owner may open outside a window

        HttpResponse<String> response = get(estado(local.slug()), null);

        JsonNode estado = json(response);
        assertThat(estado.get("servicio").asString()).isEqualTo("CERRADO");
        assertThat(estado.get("puedeParticipar").asBoolean()).isFalse();
        assertThat(estado.get("proximaApertura").asString()).isEqualTo("2026-11-10T01:00:00Z");
        assertThat(estado.propertyNames()).containsExactlyInAnyOrder("nombre", "qrSlug", "servicio", "puedeParticipar",
                "proximaApertura");
        assertThat(response.body()).doesNotContain(local.id().toString()).doesNotContain(sesion.toString());
    }

    @Test
    void joiningOutsideEveryWindowIsRefusedBeforeAnyRowIsWritten() {
        reloj.fijar(FUERA);
        Local local = conHorario("America/Bogota");
        UUID sesion = abrirSesion(local);
        int dispositivos = contar("SELECT count(*) FROM operacion.dispositivo");

        assertProblema(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false)), 409, "OUTSIDE_SCHEDULE");

        assertThat(contar("SELECT count(*) FROM operacion.participacion WHERE sesion_id = ?", sesion)).isZero();
        assertThat(contar("SELECT count(*) FROM operacion.dispositivo")).isEqualTo(dispositivos);
        reloj.fijar(DENTRO);
        assertThat(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false)).statusCode()).isEqualTo(201);
    }

    @Test
    void windowsAreReadInTheEstablishmentZoneNotTheServerZone() {
        // 02:00Z is inside Monday 20:00-23:00 in Bogota but outside it for an establishment kept in UTC.
        reloj.fijar(DENTRO);
        Local bogota = conHorario("America/Bogota");
        Local utc = conHorario("UTC");
        abrirSesion(bogota);
        abrirSesion(utc);

        assertThat(json(get(estado(bogota.slug()), null)).get("servicio").asString()).isEqualTo("ABIERTO");
        JsonNode enUtc = json(get(estado(utc.slug()), null));
        assertThat(enUtc.get("servicio").asString()).isEqualTo("CERRADO");
        assertThat(enUtc.get("proximaApertura").asString()).isEqualTo("2026-11-16T20:00:00Z");
        assertProblema(unirse(utc.slug(), cuerpo("WEB", POLICY_VERSION, true, false)), 409, "OUTSIDE_SCHEDULE");
    }

    @Test
    void withoutWindowsTheQrFollowsTheSessionAlone() {
        reloj.fijar(FUERA);
        Local local = localListoParaAbrir();

        JsonNode cerrado = json(get(estado(local.slug()), null));
        assertThat(cerrado.get("servicio").asString()).isEqualTo("CERRADO");
        assertThat(cerrado.has("proximaApertura")).isFalse();

        abrirSesion(local);
        assertThat(json(get(estado(local.slug()), null)).get("servicio").asString()).isEqualTo("ABIERTO");
        assertThat(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false)).statusCode()).isEqualTo(201);
    }

    @Test
    void insideAWindowWithoutASessionTheStatusIsClosedWithoutANextOpening() {
        reloj.fijar(DENTRO);
        Local local = conHorario("America/Bogota");

        JsonNode estado = json(get(estado(local.slug()), null));

        assertThat(estado.get("servicio").asString()).isEqualTo("CERRADO");
        assertThat(estado.has("proximaApertura")).isFalse();
        assertProblema(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false)), 409, "NO_ACTIVE_SESSION");
    }
}
