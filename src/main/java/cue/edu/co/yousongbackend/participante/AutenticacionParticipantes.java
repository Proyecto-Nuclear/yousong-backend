package cue.edu.co.yousongbackend.participante;

import cue.edu.co.yousongbackend.participante.VerificacionCredencial.Aceptada;
import cue.edu.co.yousongbackend.participante.VerificacionCredencial.Rechazada;
import cue.edu.co.yousongbackend.sesion.SesionService;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decides whether a participant bearer token may be used, and why not when it may not.
 *
 * <p>The session is checked first: once it is no longer in operation the answer is always
 * {@code SESSION_ENDED} (also for credentials the close revoked), so the client knows to clear everything.
 * Otherwise a revoked or expired credential or device is just {@code INVALID_CREDENTIAL}.
 */
@Service
public class AutenticacionParticipantes {

    private final CredencialParticipanteRepository credenciales;
    private final Clock clock;

    AutenticacionParticipantes(CredencialParticipanteRepository credenciales, Clock clock) {
        this.credenciales = credenciales;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public VerificacionCredencial verificar(String token) {
        if (!TokenParticipante.formatoValido(token)) {
            return Rechazada.invalida();
        }
        List<CredencialParticipante> encontradas = credenciales.buscarPorHash(TokenParticipante.hash(token));
        if (encontradas.size() != 1) {
            return Rechazada.invalida();
        }
        CredencialParticipante credencial = encontradas.getFirst();
        if (!SesionService.enOperacion(credencial.estadoSesion())) {
            return Rechazada.sesionTerminada();
        }
        Instant ahora = clock.instant();
        boolean vigente = credencial.revocadaAt() == null && credencial.dispositivoRevocadoAt() == null
                && credencial.expiraAt().isAfter(ahora) && credencial.dispositivoExpiraAt().isAfter(ahora);
        if (!vigente) {
            return Rechazada.invalida();
        }
        return new Aceptada(new ParticipanteAutenticado(credencial.participacionId(), credencial.sesionId(),
                credencial.establecimientoId(), credencial.seudonimo(), credencial.dispositivoId()));
    }
}
