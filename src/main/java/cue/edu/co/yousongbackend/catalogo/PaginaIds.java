package cue.edu.co.yousongbackend.catalogo;

import java.util.List;

/** Raw outcome of search.list: the matching video ids in relevance order and the next page token. */
public record PaginaIds(List<String> videoIds, String siguientePagina) {
}
