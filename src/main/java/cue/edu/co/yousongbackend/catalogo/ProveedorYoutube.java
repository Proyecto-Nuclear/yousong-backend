package cue.edu.co.yousongbackend.catalogo;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cue.edu.co.yousongbackend.catalogo.YoutubeException.Tipo;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * The only way the application talks to YouTube: refuses when the catalog is not configured or the daily
 * quota is gone (without touching the network), counts the units it spends, and remembers when the provider
 * itself reports the quota exhausted. Throws {@link YoutubeException}.
 *
 * <p>The search step (the 100-unit search.list) is cached on its own, so when videos.list fails a retry
 * repeats only the 1-unit details call. Nothing here runs inside a cache compute: provider calls happen
 * outside, and the result is stored afterwards.
 */
@Component
public class ProveedorYoutube {

    private final YoutubeClient cliente;
    private final YoutubeProperties propiedades;
    private final CuotaYoutube cuota;
    private final Cache<String, PaginaIds> busquedas;

    ProveedorYoutube(YoutubeClient cliente, YoutubeProperties propiedades, CuotaYoutube cuota) {
        this.cliente = cliente;
        this.propiedades = propiedades;
        this.cuota = cuota;
        this.busquedas = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(Duration.ofMinutes(propiedades.searchCacheMinutes()))
                .build();
    }

    /** A staff search: {@link #buscar(String, String, OrigenBusqueda)} with {@link OrigenBusqueda#STAFF}. */
    public PaginaVideos buscar(String consulta, String pagina) {
        return buscar(consulta, pagina, OrigenBusqueda.STAFF);
    }

    /**
     * search.list followed by videos.list: every match with its details, unavailable ones included. A
     * participant search reserves its worst-case cost first (100 units on a search-id miss, plus one details
     * batch), so a search refused by the participant share or the establishment budget never calls the
     * provider. A search-id cache hit only costs the details call.
     */
    public PaginaVideos buscar(String consulta, String pagina, OrigenBusqueda origen) {
        permitir();
        String clave = consulta.toLowerCase(Locale.ROOT) + "\u0000" + (pagina == null ? "" : pagina);
        PaginaIds cacheada = busquedas.getIfPresent(clave);
        boolean reservada = !origen.esStaff();
        if (reservada) {
            int lotes = YoutubeClient.lotes(cacheada == null ? propiedades.maxResults() : cacheada.videoIds().size());
            int busqueda = cacheada == null ? CuotaYoutube.COSTO_BUSQUEDA : 0;
            cuota.reservar(origen.establecimientoId(), busqueda + CuotaYoutube.COSTO_DETALLES * lotes);
        }
        PaginaIds ids = cacheada;
        if (ids == null) {
            ids = ejecutar(reservada ? 0 : CuotaYoutube.COSTO_BUSQUEDA, () -> cliente.buscar(consulta, pagina));
            busquedas.put(clave, ids);
        }
        if (ids.videoIds().isEmpty()) {
            return new PaginaVideos(List.of(), ids.siguientePagina());
        }
        List<String> videoIds = ids.videoIds();
        List<VideoYoutube> videos = reservada ? ejecutar(0, () -> cliente.obtener(videoIds)) : detalles(videoIds);
        return new PaginaVideos(videos, ids.siguientePagina());
    }

    /** videos.list for the given ids (batches of 50). Ids the provider does not know are absent. */
    public List<VideoYoutube> obtener(Collection<String> videoIds) {
        permitir();
        return detalles(videoIds);
    }

    private List<VideoYoutube> detalles(Collection<String> videoIds) {
        return ejecutar(CuotaYoutube.COSTO_DETALLES * YoutubeClient.lotes(videoIds.size()), () -> cliente.obtener(videoIds));
    }

    private void permitir() {
        if (!propiedades.configurada()) {
            throw new YoutubeException(Tipo.NOT_CONFIGURED);
        }
        if (!cuota.disponible()) {
            throw new YoutubeException(Tipo.QUOTA_EXCEEDED);
        }
    }

    private <T> T ejecutar(int unidades, java.util.function.Supplier<T> llamada) {
        cuota.consumir(unidades);
        try {
            return llamada.get();
        } catch (YoutubeException e) {
            if (e.tipo() == Tipo.QUOTA_EXCEEDED) {
                cuota.agotar();
            }
            throw e;
        }
    }
}
