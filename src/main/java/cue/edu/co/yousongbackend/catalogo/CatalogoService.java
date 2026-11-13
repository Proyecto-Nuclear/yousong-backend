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
        Map<String, String> verificados = new HashMap<>();
        if (!pagina.videos().isEmpty()) {
            canciones.findByYoutubeVideoIdIn(pagina.videos().stream().map(VideoYoutube::videoId).toList())
                    .forEach(c -> verificados.put(c.getYoutubeVideoId(), c.getArtistaVerificado()));
        }
        List<CancionBusquedaResponse> resultados = pagina.videos().stream().map(video -> {
            String verificado = verificados.get(video.videoId());
            return new CancionBusquedaResponse(video.videoId(), video.titulo(),
                    ArtistaDesdeCanal.etiqueta(verificado, video.canal()), ArtistaDesdeCanal.verificado(verificado),
                    video.canal(), video.duracionSegundos(), video.miniaturaUrl());
        }).toList();
        return new BusquedaResponse(resultados, pagina.siguientePagina());
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
