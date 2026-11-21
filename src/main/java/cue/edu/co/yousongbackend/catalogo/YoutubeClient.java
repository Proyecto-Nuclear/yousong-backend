package cue.edu.co.yousongbackend.catalogo;

import java.util.Collection;
import java.util.List;

/**
 * Port to the video provider. Implementations throw {@link YoutubeException} for every provider failure
 * and never expose the API key.
 */
public interface YoutubeClient {

    /** videos.list accepts at most this many ids per call. */
    int LOTE_MAXIMO = 50;

    /** search.list: music videos matching the query. Only ids are returned; details come from {@link #obtener}. */
    PaginaIds buscar(String consulta, String pagina);

    /**
     * videos.list: details of the given videos, in request order. Videos the provider does not know are
     * absent from the result. Unavailable ones are returned with {@code disponible=false}.
     */
    List<VideoYoutube> obtener(Collection<String> videoIds);

    /**
     * videos.list with {@code chart=mostPopular} in the Music category (10): YouTube's most popular music videos
     * of {@code region} (ISO 3166-1 alpha-2), at most {@code maximo} (1..50), in chart order, unavailable ones
     * included (with {@code disponible=false}). One call, one quota unit.
     */
    List<VideoYoutube> masPopulares(String region, int maximo);

    /** Quota units one {@link #obtener} over {@code cantidad} ids costs: 1 per batch of up to 50. */
    static int lotes(int cantidad) {
        return (cantidad + LOTE_MAXIMO - 1) / LOTE_MAXIMO;
    }
}
