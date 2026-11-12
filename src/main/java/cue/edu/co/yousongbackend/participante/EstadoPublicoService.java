package cue.edu.co.yousongbackend.participante;

import cue.edu.co.yousongbackend.establecimiento.Establecimiento;
import cue.edu.co.yousongbackend.establecimiento.HorarioSemanal;
import cue.edu.co.yousongbackend.establecimiento.HorarioService;
import cue.edu.co.yousongbackend.sesion.Sesion;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * The one place that decides what the public sees of an establishment: the QR status endpoint and the public
 * queue (REST and live stream) must always agree. Precedence: inactive (CERRADO), maintenance, system pause,
 * weekly schedule (CERRADO outside every window), and finally the state of the session in operation.
 */
@Component
public class EstadoPublicoService {

    /** The public state and, when closed only because of the schedule, the next time a window opens. */
    public record EstadoPublico(EstadoServicio servicio, Instant proximaApertura) {
    }

    private final HorarioService horarios;
    private final Clock clock;

    EstadoPublicoService(HorarioService horarios, Clock clock) {
        this.horarios = horarios;
        this.clock = clock;
    }

    /** @param sesionEnOperacion looked up only when the earlier rules do not already decide the state */
    public EstadoPublico resolver(Establecimiento establecimiento, Supplier<Optional<Sesion>> sesionEnOperacion) {
        if (!establecimiento.isActivo()) {
            return new EstadoPublico(EstadoServicio.CERRADO, null);
        }
        if (establecimiento.isEnMantenimiento()) {
            return new EstadoPublico(EstadoServicio.MANTENIMIENTO, null);
        }
        if (establecimiento.isPausado()) {
            return new EstadoPublico(EstadoServicio.PAUSADO, null);
        }
        HorarioSemanal horario = horarios.horarioDe(establecimiento);
        Instant ahora = clock.instant();
        if (!horario.abiertoEn(ahora)) {
            return new EstadoPublico(EstadoServicio.CERRADO, horario.proximaApertura(ahora).orElse(null));
        }
        EstadoServicio servicio = sesionEnOperacion.get()
                .map(sesion -> EstadoServicio.de(sesion.getEstado())).orElse(EstadoServicio.CERRADO);
        return new EstadoPublico(servicio, null);
    }
}
