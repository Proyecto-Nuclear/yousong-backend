package cue.edu.co.yousongbackend.catalogo;

/**
 * A video as YouTube describes it. {@code canal} is the title of the uploading channel (YouTube has no
 * artist field); {@code duracionSegundos} and {@code miniaturaUrl} are null when the provider gives none.
 */
public record VideoYoutube(
        String videoId,
        String titulo,
        String canal,
        String miniaturaUrl,
        Integer duracionSegundos,
        boolean disponible,
        String motivoNoDisponible) {
}
