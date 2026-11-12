package cue.edu.co.yousongbackend.repertorio;

import java.time.Instant;
import java.util.UUID;

/** A version in the history of the default lists (no songs). */
public record ListaDefectoResumenResponse(
        UUID id,
        String nombre,
        int version,
        PoliticaRotacion politicaRotacion,
        Instant creadoAt,
        int totalCanciones) {
}
