package cue.edu.co.yousongbackend.catalogo;

import cue.edu.co.yousongbackend.catalogo.YoutubeException.Tipo;
import java.util.Collection;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The only way the application talks to YouTube: refuses when the catalog is not configured or the daily
 * quota is gone (without touching the network), counts the units it spends, and remembers when the provider
 * itself reports the quota exhausted. Throws {@link YoutubeException}.
 */
@Component
public class ProveedorYoutube {

    private final YoutubeClient cliente;
    private final YoutubeProperties propiedades;
    private final CuotaYoutube cuota;

    ProveedorYoutube(YoutubeClient cliente, YoutubeProperties propiedades, CuotaYoutube cuota) {
        this.cliente = cliente;
        this.propiedades = propiedades;
        this.cuota = cuota;
    }

    /** search.list followed by videos.list: every match with its details, unavailable ones included. */
    public PaginaVideos buscar(String consulta, String pagina) {
        permitir();
        PaginaIds ids = ejecutar(CuotaYoutube.COSTO_BUSQUEDA, () -> cliente.buscar(consulta, pagina));
        if (ids.videoIds().isEmpty()) {
            return new PaginaVideos(List.of(), ids.siguientePagina());
        }
        return new PaginaVideos(detalles(ids.videoIds()), ids.siguientePagina());
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
