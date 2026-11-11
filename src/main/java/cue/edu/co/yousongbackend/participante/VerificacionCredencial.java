package cue.edu.co.yousongbackend.participante;

/** Outcome of checking a participant credential. */
public sealed interface VerificacionCredencial {

    /**
     * The credential is not usable: missing, malformed, unknown, expired, revoked by leaving or replaced by a
     * rejoin of the same device, or its device was revoked or expired.
     */
    String INVALID_CREDENTIAL = "INVALID_CREDENTIAL";

    /** The credential belonged to a session that is no longer in operation: the client must forget it. */
    String SESSION_ENDED = "SESSION_ENDED";

    record Aceptada(ParticipanteAutenticado participante) implements VerificacionCredencial {
    }

    record Rechazada(String code, String detail) implements VerificacionCredencial {

        static Rechazada invalida() {
            return new Rechazada(INVALID_CREDENTIAL, "The participant credential is missing, invalid or expired");
        }

        static Rechazada sesionTerminada() {
            return new Rechazada(SESSION_ENDED, "The session this credential belonged to has ended");
        }
    }
}
