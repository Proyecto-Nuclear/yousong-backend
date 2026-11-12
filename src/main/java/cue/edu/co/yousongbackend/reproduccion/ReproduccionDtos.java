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

    /**
     * Why staff skipped a song (REP-04 "Saltar una canción con motivo y registro del operador"); stored in
     * {@code reproduccion.motivo_codigo}. A SALTADA result without a reason is a {@link #SALTO_MANUAL}.
     */
    public enum MotivoSalto {
        /** Staff decided to skip it, no other reason given. */
        SALTO_MANUAL,
        /** The content is not suitable for the venue. */
        CONTENIDO_INAPROPIADO,
        /** The song is blocked in the establishment. */
        CONTENIDO_BLOQUEADO,
        /** The playback did not work well (sound, video, buffering). */
        PROBLEMA_TECNICO
    }

    /** Reason of a request staff removed from the queue (recorded in its event and audit row). */
    public static final String ELIMINADA_POR_PERSONAL = "ELIMINADA_POR_PERSONAL";

    /** {@code motivoCodigo} is only accepted with SALTADA: a {@link MotivoSalto}, SALTO_MANUAL when absent. */
    public record FinReproduccionRequest(@NotNull ResultadoFin resultado, String motivoCodigo) {
    }

    /** Optional body of {@code POST .../reproduccion/saltar}. */
    public record SaltoRequest(String motivoCodigo) {
    }

    /** {@code origen} is SOLICITUD or DEFECTO; a default-list song has no {@code solicitudId} and 0 votes. */
    public record InicioReproduccionResponse(UUID reproduccionId, OrigenReproduccion origen, UUID solicitudId,
                                             CancionCola cancion, Instant iniciadaAt, long votos, long colaVersion) {
    }

    /** {@code motivoCodigo} is the skip reason of a SALTADA playback, null otherwise. */
    public record FinReproduccionResponse(UUID reproduccionId, UUID solicitudId, ResultadoReproduccion resultado,
                                          String motivoCodigo, Instant terminadaAt, long colaVersion) {
    }

    /** A skip: the playback that ended, and what started after it (null when nothing is left to play). */
    public record SaltoResponse(FinReproduccionResponse fin, InicioReproduccionResponse siguiente) {
    }

    /** A request staff took out of the queue. */
    public record EliminacionResponse(UUID solicitudId, EstadoSolicitud estado, String motivoCodigo, long colaVersion) {
    }
}
