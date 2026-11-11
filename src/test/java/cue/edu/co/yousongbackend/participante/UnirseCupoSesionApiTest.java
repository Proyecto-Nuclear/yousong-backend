package cue.edu.co.yousongbackend.participante;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** A session holds at most K participations, and running out of pseudonyms is a controlled refusal. */
@TestPropertySource(properties = "app.participante.max-per-session=2")
class UnirseCupoSesionApiTest extends ParticipanteApiTestSupport {

    @MockitoSpyBean
    private GeneradorSeudonimos seudonimos;

    private int filasDe(UUID sesionId) {
        return contar("SELECT count(*) FROM operacion.participacion WHERE sesion_id = ?", sesionId);
    }

    private int filasGlobales() {
        return contar("SELECT (SELECT count(*) FROM operacion.dispositivo) + (SELECT count(*) FROM operacion.consentimiento)"
                + " + (SELECT count(*) FROM operacion.sesion_acceso)");
    }

    @Test
    void aFullSessionRefusesTheJoinAndCreatesNothing() {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);
        unirse(local);
        unirse(local);
        int filas = filasGlobales();

        assertProblema(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, true)), 409, "SESSION_FULL");

        assertThat(filasDe(sesionId)).isEqualTo(2);
        assertThat(filasGlobales()).isEqualTo(filas);
    }

    @Test
    void theCapIsPerSession() {
        Local local = localListoParaAbrir();
        UUID primera = abrirSesion(local);
        unirse(local);
        unirse(local);
        cerrarSesion(primera);
        UUID segunda = abrirSesion(local);

        assertThat(unirse(local)).startsWith("pt_");
        assertThat(filasDe(segunda)).isEqualTo(1);
    }

    @Test
    void whenNoFreePseudonymIsFoundTheJoinIsUnavailableAndCreatesNothing() {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);
        doReturn("p-000000").when(seudonimos).nuevo();
        unirse(local);
        int filas = filasGlobales();

        assertProblema(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, true)), 503, "JOIN_UNAVAILABLE");

        assertThat(filasDe(sesionId)).isEqualTo(1);
        assertThat(filasGlobales()).isEqualTo(filas);
    }
}
