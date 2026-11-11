package cue.edu.co.yousongbackend.acceso;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ends participant access. Rows are stamped, never deleted: a credential revoked because its session closed
 * must still be recognized, so the client gets {@code SESSION_ENDED} instead of a generic rejection.
 */
@Component
public class AccesoParticipantes {

    private final SesionAccesoRepository credenciales;
    private final DispositivoRepository dispositivos;

    AccesoParticipantes(SesionAccesoRepository credenciales, DispositivoRepository dispositivos) {
        this.credenciales = credenciales;
        this.dispositivos = dispositivos;
    }

    /** Part of closing a session: must run inside the transaction that closes it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revocarDeLaSesion(UUID sesionId, Instant ahora) {
        credenciales.revocarDeLaSesion(sesionId, ahora);
        dispositivos.revocarDeLaSesion(sesionId, ahora);
    }

    /** A participant leaves voluntarily: its device and every credential of that device stop working. */
    @Transactional
    public void revocarDispositivo(UUID dispositivoId, Instant ahora) {
        credenciales.revocarDelDispositivo(dispositivoId, ahora);
        dispositivos.revocar(dispositivoId, ahora);
    }
}
