package cue.edu.co.yousongbackend.cola;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** Abuse limits of the queue: actions per participant per minute, and active requests per session. */
@TestPropertySource(properties = {"app.participante.queue-actions-per-minute=3", "app.cola.max-active-per-session=2"})
class ColaLimitesApiTest extends ColaApiTestSupport {

    @Test
    void aParticipantMakingTooManyQueueActionsIsLimitedBeforeAnyWork() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        String token = unirse(local);
        UUID primera = solicitada(unirse(local), videoGuardado());
        UUID segunda = solicitada(unirse(local), videoGuardado());
        assertThat(votar(token, primera).statusCode()).isEqualTo(200);
        assertThat(votar(token, segunda).statusCode()).isEqualTo(200);
        // The third action is still allowed (and rejected for its own reason); the fourth is limited.
        assertProblema(votar(token, primera), 409, "ALREADY_VOTED");
        long version = colaVersion(sesion);

        assertProblema(votar(token, UUID.randomUUID()), 429, "RATE_LIMITED");
        assertProblema(solicitar(token, "not a video"), 429, "RATE_LIMITED");
        assertThat(colaVersion(sesion)).isEqualTo(version);
    }

    @Test
    void theLimitIsPerParticipant() {
        Local local = localAbierto();
        String token = unirse(local);
        UUID solicitud = solicitada(unirse(local), videoGuardado());
        for (int i = 0; i < 3; i++) {
            votar(token, UUID.randomUUID());
        }
        assertProblema(votar(token, solicitud), 429, "RATE_LIMITED");

        assertThat(votar(unirse(local), solicitud).statusCode()).isEqualTo(200);
    }

    @Test
    void aSessionWithTooManyActiveRequestsRejectsNewOnesButStillTakesVotes() {
        Local local = localAbierto();
        UUID sesion = sesionDe(local);
        UUID primera = solicitada(unirse(local), videoGuardado());
        solicitada(unirse(local), videoGuardado());

        assertProblema(solicitar(unirse(local), videoGuardado()), 409, "QUEUE_FULL");
        assertThat(contar("SELECT count(*) FROM operacion.solicitud WHERE sesion_id = ?", sesion)).isEqualTo(2);
        assertThat(votar(unirse(local), primera).statusCode()).isEqualTo(200);
    }
}
