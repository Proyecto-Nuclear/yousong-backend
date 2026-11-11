package cue.edu.co.yousongbackend.participante;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** At most M joins per minute into one establishment, whatever the addresses (bounds a distributed attack). */
@TestPropertySource(properties = {"app.participante.join-per-minute-per-address=100000",
        "app.participante.join-per-minute-per-establishment=2"})
class UnirseLimitePorEstablecimientoApiTest extends ParticipanteApiTestSupport {

    @Autowired
    private LimitadorUniones limitador;

    @BeforeEach
    void ventanaNueva() {
        limitador.reiniciar();
    }

    @Test
    void theJoinAfterTheEstablishmentAllowanceIsRateLimitedAndCreatesNothing() {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);
        unirse(local);
        unirse(local);
        int dispositivos = contar("SELECT count(*) FROM operacion.dispositivo");
        int credenciales = contar("SELECT count(*) FROM operacion.sesion_acceso");

        assertProblema(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, true)), 429, "RATE_LIMITED");

        assertThat(contar("SELECT count(*) FROM operacion.participacion WHERE sesion_id = ?", sesionId)).isEqualTo(2);
        assertThat(contar("SELECT count(*) FROM operacion.dispositivo")).isEqualTo(dispositivos);
        assertThat(contar("SELECT count(*) FROM operacion.sesion_acceso")).isEqualTo(credenciales);
    }

    @Test
    void aLimitedEstablishmentDoesNotAffectAnotherOne() {
        Local limitado = localAbierto();
        Local otro = localAbierto();
        unirse(limitado);
        unirse(limitado);
        assertProblema(unirse(limitado.slug(), cuerpo("WEB", POLICY_VERSION, true, false)), 429, "RATE_LIMITED");

        assertThat(unirse(otro)).startsWith("pt_");
        assertThat(unirse(otro)).startsWith("pt_");
    }
}
