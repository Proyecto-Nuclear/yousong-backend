package cue.edu.co.yousongbackend.acceso;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ends participant access. Rows are stamped, never deleted: a credential revoked because its session closed
 * must still be recognized, so the client gets {@code SESSION_ENDED} instead of a generic rejection.
 *
 * <p>Leaving or a session closing ends the credentials only. The device stays valid, so the same phone is
 * recognized when it comes back (and gets its participation back instead of a second one). Only an explicit
 * {@link #revocarDispositivo} ends the device itself.
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
    }

    /** A participant leaves voluntarily, or rejoins and gets a fresh credential: the old ones stop working. */
    @Transactional
    public void revocarCredencialesDeLaParticipacion(UUID participacionId, Instant ahora) {
        credenciales.revocarDeLaParticipacion(participacionId, ahora);
    }

    /**
     * Administrative revocation of a device: the device and every credential of it stop working, and its
     * secret is no longer recognized on join (the phone would get a brand-new device).
     */
    @Transactional
    public void revocarDispositivo(UUID dispositivoId, Instant ahora) {
        credenciales.revocarDelDispositivo(dispositivoId, ahora);
        dispositivos.revocar(dispositivoId, ahora);
    }
}
