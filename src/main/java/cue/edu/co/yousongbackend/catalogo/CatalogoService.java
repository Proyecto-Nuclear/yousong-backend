package cue.edu.co.yousongbackend.catalogo;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Searches YouTube for songs. Identical searches are answered from memory (the quota is small: a search
 * costs 100 of the 10,000 daily units); the cache holds what the provider said, and the artist label is
 * resolved on every call so a song that gets a verified artist shows it at once.
 */
@Service
public class CatalogoService {

    private static final Pattern PAGINA = Pattern.compile("[A-Za-z0-9_=-]{1,100}");

    private final ProveedorYoutube proveedor;
    private final CancionRepository canciones;
    private final Cache<String, PaginaVideos> cache;

    CatalogoService(ProveedorYoutube proveedor, CancionRepository canciones, YoutubeProperties propiedades) {
        this.proveedor = proveedor;
        this.canciones = canciones;
        this.cache = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(Duration.ofMinutes(propiedades.searchCacheMinutes()))
                .build();
    }

    /**
     * A cache hit answers for free; only a miss reaches the provider and is charged to {@code origen}.
     *
     * @param consulta what to look for (2 to 100 characters once trimmed and with spaces collapsed)
     * @param pagina   the {@code siguientePagina} of the previous response, or null for the first page
     * @param origen   the participant's or the owner's establishment (bounded by its share and daily budget)
     */
    public BusquedaResponse buscar(String consulta, String pagina, OrigenBusqueda origen) {
        String texto = ConsultaCatalogo.normalizar(consulta);
        String token = token(pagina);
        String clave = texto.toLowerCase(Locale.ROOT) + "\u0000" + (token == null ? "" : token);

        // Never fetch inside a Caffeine compute: it holds a bin lock, so a slow provider would block other
        // queries. Check, fetch outside, then store (two identical concurrent misses may both fetch).
        PaginaVideos resultado = cache.getIfPresent(clave);
        if (resultado == null) {
            try {
                PaginaVideos pagina1 = proveedor.buscar(texto, token, origen);
                List<VideoYoutube> disponibles = pagina1.videos().stream().filter(VideoYoutube::disponible).toList();
                resultado = new PaginaVideos(disponibles, pagina1.siguientePagina());
            } catch (YoutubeException e) {
                throw e.aApi();
            }
            cache.put(clave, resultado);
        }
        return respuesta(resultado);
    }

    private BusquedaResponse respuesta(PaginaVideos pagina) {
        return new BusquedaResponse(canciones(pagina.videos()), pagina.siguientePagina());
    }

    /**
     * Provider videos as songs of the catalog, in the same order: what YouTube said, plus what only we know of
     * the songs already stored. The artist is the verified one when the song has it; the genre is the live one
     * from YouTube's topics, except that a stored genre of another source (chosen on purpose) wins, like it does
     * when the metadata is written ({@link CancionRepository#actualizarMetadatos}).
     */
    public List<CancionBusquedaResponse> canciones(List<VideoYoutube> videos) {
        Map<String, Cancion> guardadas = new HashMap<>();
        if (!videos.isEmpty()) {
            canciones.findByYoutubeVideoIdIn(videos.stream().map(VideoYoutube::videoId).toList())
                    .forEach(c -> guardadas.put(c.getYoutubeVideoId(), c));
        }
        return videos.stream().map(video -> {
            Cancion guardada = guardadas.get(video.videoId());
            String verificado = guardada == null ? null : guardada.getArtistaVerificado();
            return new CancionBusquedaResponse(video.videoId(), video.titulo(),
                    ArtistaDesdeCanal.etiqueta(verificado, video.canal()), ArtistaDesdeCanal.verificado(verificado),
                    video.canal(), video.duracionSegundos(), video.miniaturaUrl(), genero(video, guardada));
        }).toList();
    }

    private static String genero(VideoYoutube video, Cancion guardada) {
        if (guardada != null && guardada.getGeneroPropio() != null
                && !GeneroMusical.FUENTE.equals(guardada.getFuenteClasificacion())) {
            return guardada.getGeneroPropio();
        }
        return video.genero();
    }

    private static String token(String pagina) {
        if (pagina == null || pagina.isBlank()) {
            return null;
        }
        String token = pagina.strip();
        if (!PAGINA.matcher(token).matches()) {
            throw ApiException.badRequest("INVALID_PAGE", "The page token is not valid");
        }
        return token;
    }
}
