package cue.edu.co.yousongbackend.catalogo;

import java.util.List;

/** A page of search results; pass {@code siguientePagina} back as {@code pagina} to get the next one (null on the last). */
public record BusquedaResponse(List<CancionBusquedaResponse> resultados, String siguientePagina) {
}
