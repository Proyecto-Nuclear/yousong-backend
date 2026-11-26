package cue.edu.co.yousongbackend.analitica;

import cue.edu.co.yousongbackend.catalogo.CancionBusquedaResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The published history of an establishment (see {@link AnaliticaHistoricaService}). Every per-session list holds
 * the same sessions in the same order as {@code jornadas}, oldest first. No identity of any kind is part of it.
 *
 * @param publicacion   the newest publication among those sessions; null when none is published
 * @param canales       participations per join channel, both channels always present
 * @param ranking       at most 8 songs, most voted first
 */
public record AnaliticaResponse(Publicacion publicacion, List<Jornada> jornadas, List<Participacion> participacion,
                                Map<String, Long> canales, List<Popular> ranking,
                                List<GenerosJornada> generosPorHora) {

    /** Version and cut-off of a published batch. */
    public record Publicacion(long version, Instant publicadaAt, Instant corteEventosAt) {
    }

    public record Jornada(UUID sesionId, LocalDate fechaOperativa) {
    }

    /** Participations of a session, and how many of them requested or voted at least once. */
    public record Participacion(UUID sesionId, long ingresaron, long activas) {
    }

    /** A song, its votes (initial vote included once) and its completed playbacks in the period. */
    public record Popular(CancionBusquedaResponse cancion, long votos, long completadas) {
    }

    /** The genre cells of one session; {@code diaSemana} is the ISO day of its operating date (Monday = 1). */
    public record GenerosJornada(UUID sesionId, LocalDate fechaOperativa, int diaSemana, List<CeldaGenero> celdas) {
    }
}
