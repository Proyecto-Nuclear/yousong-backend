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

    /**
     * A song of the list. Everything but the ids is null until the catalog has enriched the video.
     * {@code artista} is a display label (the verified artist, or derived from the channel);
     * {@code artistaVerificado} is true only when the song has a verified artist.
     */
    public record ItemListaResponse(
            int posicion,
            UUID cancionId,
            String youtubeVideoId,
            String titulo,
            String artista,
            boolean artistaVerificado,
            String canal,
            Integer duracionSegundos,
            String miniaturaUrl) {
    }
}
