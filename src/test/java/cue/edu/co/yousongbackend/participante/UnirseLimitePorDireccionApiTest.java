package cue.edu.co.yousongbackend.participante;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/** At most N joins per minute from one client address (every test client calls from 127.0.0.1). */
@TestPropertySource(properties = "app.participante.join-per-minute-per-address=3")
class UnirseLimitePorDireccionApiTest extends ParticipanteApiTestSupport {

    @Autowired
    private LimitadorUniones limitador;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void ventanaNueva() {
        limitador.reiniciar();
    }

    private int filasCreadas() {
        return contar("SELECT (SELECT count(*) FROM operacion.dispositivo) + (SELECT count(*) FROM operacion.participacion)"
                + " + (SELECT count(*) FROM operacion.consentimiento) + (SELECT count(*) FROM operacion.sesion_acceso)");
    }

    @Test
    void theJoinAfterTheAllowanceIsRateLimitedAndCreatesNothing() {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);

        for (int i = 0; i < 3; i++) {
            unirse(local);
        }
        int filas = filasCreadas();

        HttpResponse<String> limitada = unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, true));

        assertProblema(limitada, 429, "RATE_LIMITED");
        assertThat(limitada.body()).doesNotContain("127.0.0.1");
        assertThat(contar("SELECT count(*) FROM operacion.participacion WHERE sesion_id = ?", sesionId)).isEqualTo(3);
        assertThat(filasCreadas()).isEqualTo(filas);
    }

    @Test
    void rejoinsWithADeviceTokenCountTowardTheAllowance() {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);
        HttpResponse<String> primero = unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false));
        assertThat(primero.statusCode()).as(primero.body()).isEqualTo(201);
        String dispositivoToken = json(primero).get("dispositivoToken").asString();
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> rejoin = unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false, dispositivoToken));
            assertThat(rejoin.statusCode()).as(rejoin.body()).isEqualTo(201);
        }
        int filas = filasCreadas();

        assertProblema(unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false, dispositivoToken)),
                429, "RATE_LIMITED");

        assertThat(contar("SELECT count(*) FROM operacion.participacion WHERE sesion_id = ?", sesionId)).isEqualTo(1);
        assertThat(filasCreadas()).isEqualTo(filas);
    }

    @Test
    void theAddressAllowanceCoversEveryEstablishment() {
        Local primero = localAbierto();
        Local segundo = localAbierto();

        unirse(primero);
        unirse(primero);
        unirse(segundo);

        assertProblema(unirse(segundo.slug(), cuerpo("WEB", POLICY_VERSION, true, false)), 429, "RATE_LIMITED");
    }

    @Test
    void joinsBelowTheAllowanceSucceed() {
        Local local = localAbierto();

        for (int i = 0; i < 3; i++) {
            assertThat(unirse(local)).startsWith("pt_");
        }
    }

    /**
     * A closing session holds an exclusive lock on its row. A limited join must answer at once instead of
     * queueing behind it: the limit is checked before the session lock is requested.
     */
    @Test
    void aLimitedJoinTakesNoSessionLock() throws Exception {
        Local local = localListoParaAbrir();
        UUID sesionId = abrirSesion(local);
        for (int i = 0; i < 3; i++) {
            unirse(local);
        }
        int filas = filasCreadas();

        try (Connection conexion = dataSource.getConnection()) {
            conexion.setAutoCommit(false);
            try (PreparedStatement bloqueo = conexion.prepareStatement(
                    "SELECT id FROM operacion.sesion WHERE id = ? FOR UPDATE")) {
                bloqueo.setObject(1, sesionId);
                bloqueo.executeQuery().close();
            }
            try {
                HttpResponse<String> limitada = assertTimeoutPreemptively(Duration.ofSeconds(5),
                        () -> unirse(local.slug(), cuerpo("WEB", POLICY_VERSION, true, false)));
                assertProblema(limitada, 429, "RATE_LIMITED");
            } finally {
                conexion.rollback();
            }
        }
        assertThat(filasCreadas()).isEqualTo(filas);
    }
}
