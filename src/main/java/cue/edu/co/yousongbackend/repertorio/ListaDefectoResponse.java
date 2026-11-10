package cue.edu.co.yousongbackend.repertorio;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One published version of a default list with its songs in playing order. */
public record ListaDefectoResponse(
        UUID id,
        String nombre,
        int version,
        PoliticaRotacion politicaRotacion,
        Instant creadoAt,
        List<ItemListaResponse> canciones) {

    /** A song of the list. Title and artist are null until the catalog has enriched the video. */
    public record ItemListaResponse(
            int posicion,
            UUID cancionId,
            String youtubeVideoId,
            String titulo,
            String artistaVerificado) {
    }
}
