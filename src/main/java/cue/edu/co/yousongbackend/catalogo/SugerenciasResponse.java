package cue.edu.co.yousongbackend.catalogo;

import java.util.List;

/** Search-as-you-type answer: known songs first, then search strings, at most 8 in total. */
public record SugerenciasResponse(List<SugerenciaResponse> sugerencias) {
}
