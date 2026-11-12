package cue.edu.co.yousongbackend.catalogo;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One search-as-you-type suggestion. {@code CANCION}: a song the system already knows, with everything the
 * client needs to pick it without a (paid) search. {@code BUSQUEDA}: a search string to complete the query
 * with; it has no {@code cancion}.
 */
public record SugerenciaResponse(
        Tipo tipo,
        String texto,
        @JsonInclude(JsonInclude.Include.NON_NULL) CancionBusquedaResponse cancion) {

    public enum Tipo {
        CANCION, BUSQUEDA
    }

    static SugerenciaResponse cancion(CancionBusquedaResponse cancion) {
        return new SugerenciaResponse(Tipo.CANCION, cancion.titulo(), cancion);
    }

    static SugerenciaResponse busqueda(String texto) {
        return new SugerenciaResponse(Tipo.BUSQUEDA, texto, null);
    }
}
