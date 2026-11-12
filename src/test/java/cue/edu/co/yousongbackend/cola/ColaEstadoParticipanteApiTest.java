package cue.edu.co.yousongbackend.cola;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.support.RelojDePrueba;
import cue.edu.co.yousongbackend.support.RelojDePrueba.RelojMovible;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * The participant views (their queue and their session) say the same thing as the public status: a system pause
 * and a closed schedule window show up there too, not only the state of the session.
 */
@Import(RelojDePrueba.class)
class ColaEstadoParticipanteApiTest extends ColaApiTestSupport {

    private static final String MI = "/api/mi-establecimiento";
    /** Monday 2026-11-09 20:00-23:00 in Bogota (UTC-5): 2026-11-10 01:00Z to 04:00Z. */
    private static final String LUNES_NOCHE = "[{\"diaSemana\":1,\"inicio\":\"20:00\",\"fin\":\"23:00\"}]";
    private static final Instant DENTRO = Instant.parse("2026-11-10T02:00:00Z");
    private static final Instant FUERA = Instant.parse("2026-11-10T04:30:00Z"); // Monday 23:30 in Bogota

    @Autowired
    private RelojMovible reloj;

    private String publico(Local local) {
        return json(get(estado(local.slug()), null)).get("servicio").asString();
    }

    private String deSuCola(String token) {
        return json(colaParticipante(token)).get("estado").asString();
    }

    private String deSuSesion(String token) {
        return json(get(MI_SESION, token)).get("servicio").asString();
    }

    private void lasVistasCoinciden(Local local, String token, String esperado) {
        assertThat(publico(local)).isEqualTo(esperado);
        assertThat(deSuCola(token)).as("participant queue").isEqualTo(esperado);
        assertThat(deSuSesion(token)).as("participant session").isEqualTo(esperado);
    }

    @Test
    void theParticipantViewsShowTheSystemPauseAndItsEnd() {
        reloj.fijar(Instant.now());
        Local local = localAbierto();
        String token = unirse(local);
        lasVistasCoinciden(local, token, "ABIERTO");

        assertThat(post(MI + "/pausa", local.token()).statusCode()).isEqualTo(200);
        lasVistasCoinciden(local, token, "PAUSADO");

        assertThat(post(MI + "/reanudacion", local.token()).statusCode()).isEqualTo(200);
        lasVistasCoinciden(local, token, "ABIERTO");
    }

    @Test
    void theParticipantViewsShowAClosedScheduleWindowOfAnOpenSession() {
        reloj.fijar(DENTRO);
        Local local = establecimiento("America/Bogota", true);
        listaDefecto(local.id());
        assertThat(put(MI + "/horarios", local.token(), LUNES_NOCHE).statusCode()).isEqualTo(200);
        abrirSesion(local);
        String token = unirse(local);
        lasVistasCoinciden(local, token, "ABIERTO");

        reloj.fijar(FUERA);
        lasVistasCoinciden(local, token, "CERRADO");

        reloj.fijar(DENTRO);
        lasVistasCoinciden(local, token, "ABIERTO");
    }
}
