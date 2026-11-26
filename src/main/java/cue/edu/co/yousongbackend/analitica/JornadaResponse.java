package cue.edu.co.yousongbackend.analitica;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One session computed live from the operational tables (see {@link AnaliticaJornadaService}). With
 * {@code sesion} null every count is zero and every list empty, except {@code reproducciones}, which always holds
 * both origins. No identity of any kind is part of it.
 *
 * @param enVivo  true while the session is in operation (not CERRADA)
 * @param corteAt the instant the figures were read
 * @param canales participations per join channel, both channels always present
 * @param porHora local hours in night order, from the first to the last hour with activity
 * @param estados requests per state, all eight states always present
 */
public record JornadaResponse(Sesion sesion, boolean enVivo, Instant corteAt, Participacion participacion,
                              Map<String, Long> canales, List<Hora> porHora, Embudo embudo,
                              Map<String, Long> estados, Espera espera, List<Reproducciones> reproducciones,
                              List<CeldaGenero> generosPorHora) {

    public record Sesion(UUID id, LocalDate fechaOperativa, String estado) {
    }

    public record Participacion(long ingresaron, long activas) {
    }

    /** Requests created and votes accepted during one local hour (initial votes included). */
    public record Hora(int hora, long solicitudes, long votos) {
    }

    /** Requests created, started at least once and completed (ATENDIDA with a COMPLETADA playback). */
    public record Embudo(long creadas, long iniciadas, long completadas) {
    }

    /**
     * Real wait (first start minus creation) of the started requests, and the requests still waiting reported apart.
     *
     * @param medianaS                     null when no request started
     * @param antiguedadMedianaPendientesS null when nothing is pending
     */
    public record Espera(Long medianaS, Long p95S, long iniciadas, List<Tramo> tramos, long pendientes,
                         Long antiguedadMedianaPendientesS) {
    }

    /** Started requests whose wait is in {@code [desdeMin, hastaMin)} minutes; the last bin has no upper bound. */
    public record Tramo(int desdeMin, Integer hastaMin, long total) {
    }

    /** Playback attempts of one origin by result; {@code enCurso} is the open attempt (no result yet). */
    public record Reproducciones(String origen, long completadas, long saltadas, long fallidas, long interrumpidas,
                                 long enCurso) {
    }
}
