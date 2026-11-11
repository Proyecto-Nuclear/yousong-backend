package cue.edu.co.yousongbackend.catalogo;

import java.util.List;

/** One page of search results; {@code siguientePagina} is YouTube's {@code nextPageToken} (null on the last page). */
public record PaginaVideos(List<VideoYoutube> videos, String siguientePagina) {
}
