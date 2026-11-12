package cue.edu.co.yousongbackend.reproduccion;

import cue.edu.co.yousongbackend.cola.ColaDtos.CancionCola;
import cue.edu.co.yousongbackend.cola.EstadoSolicitud;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

/** Wire shapes of the minimal staff playback driver. */
public final class ReproduccionDtos {

    private ReproduccionDtos() {
    }

    /** How a playback ended, as staff report it. INTERRUMPIDA_CIERRE is reserved for the session close. */
    public enum ResultadoFin {
        COMPLETADA(ResultadoReproduccion.COMPLETADA, EstadoSolicitud.ATENDIDA),
        SALTADA(ResultadoReproduccion.SALTADA, EstadoSolicitud.SALTADA),
        FALLIDA(ResultadoReproduccion.FALLIDA, EstadoSolicitud.FALLIDA);

        private final ResultadoReproduccion resultado;
        private final EstadoSolicitud estadoSolicitud;

        ResultadoFin(ResultadoReproduccion resultado, EstadoSolicitud estadoSolicitud) {
            this.resultado = resultado;
            this.estadoSolicitud = estadoSolicitud;
        }

        ResultadoReproduccion resultado() {
            return resultado;
        }

        EstadoSolicitud estadoSolicitud() {
            return estadoSolicitud;
        }
    }

    public record FinReproduccionRequest(@NotNull ResultadoFin resultado) {
    }

    public record InicioReproduccionResponse(UUID reproduccionId, UUID solicitudId, CancionCola cancion,
                                             Instant iniciadaAt, long votos, long colaVersion) {
    }

    public record FinReproduccionResponse(UUID reproduccionId, UUID solicitudId, ResultadoReproduccion resultado,
                                          Instant terminadaAt, long colaVersion) {
    }
}
