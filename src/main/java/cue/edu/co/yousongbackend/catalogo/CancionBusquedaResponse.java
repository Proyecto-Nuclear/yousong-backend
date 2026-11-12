package cue.edu.co.yousongbackend.catalogo;

/**
 * A search result. {@code artista} is a display label (the verified artist when the song has one, otherwise
 * derived from the channel); {@code artistaVerificado} is true only in the first case.
 */
public record CancionBusquedaResponse(
        String videoId,
        String titulo,
        String artista,
        boolean artistaVerificado,
        String canal,
        Integer duracionSegundos,
        String miniaturaUrl) {
}
