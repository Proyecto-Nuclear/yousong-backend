package cue.edu.co.yousongbackend.catalogo;

import cue.edu.co.yousongbackend.catalogo.YoutubeException.Tipo;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import cue.edu.co.yousongbackend.shared.web.LimitadorPorDireccion;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * "Lo más escuchado en Colombia": YouTube's most popular music videos of the configured region
 * ({@code app.youtube.region-code}, CO), from {@code videos.list?chart=mostPopular&videoCategoryId=10}.
 *
 * <p>Cost and freshness. One refresh is one videos.list call (1 quota unit) charged to the system
 * ({@link ProveedorYoutube#masPopulares}): never to the participants' or the owners' share. The answer is kept in
 * memory for {@code app.trends.cache-hours} (6); every request inside that window is free. Refreshes are a single
 * flight: concurrent requests that find the chart expired share one provider call (the first one leads, the others
 * wait for it, never longer than the provider timeout plus a margin). When a refresh fails the previous answer is
 * served as it is (its {@code actualizadoAt} tells how old it is) for up to {@code app.trends.max-stale-hours} (48);
 * after a failure the provider is not asked again for a minute, so a broken provider costs at most one call per
 * minute. With nothing to serve the error is the catalog's: 503 {@code CATALOG_NOT_CONFIGURED},
 * {@code CATALOG_UNAVAILABLE} or {@code CATALOG_QUOTA_EXCEEDED}.
 *
 * <p>Only songs the player can play are kept (embeddable, public, processed, not live, not blocked in the region),
 * and they are stored in {@code operacion.cancion} through the usual metadata write (genre included), so they can
 * be requested later without another lookup. A failure to store them is logged and does not fail the answer.
 *
 * <p>Abuse: at most {@code app.trends.per-minute-per-address} (600) reads per client address and minute, checked
 * first (429 {@code RATE_LIMITED}). Everything is per instance and in memory.
 */
@Slf4j
@Service
public class TendenciasService {

    static final int MAXIMO = 25;
    static final Duration ESPERA_TRAS_FALLO = Duration.ofMinutes(1);
    private static final Duration MARGEN = Duration.ofSeconds(10);

    private final ProveedorYoutube proveedor;
    private final CatalogoService catalogo;
    private final MetadatosCancionService metadatos;
    private final String region;
    private final Clock reloj;
    private final Duration vigencia;
    private final Duration maximaAntiguedad;
    private final Duration esperaMaxima;
    private final LimitadorPorDireccion limitador;
    private final AtomicReference<CompletableFuture<TendenciasResponse>> enCurso = new AtomicReference<>();

    private volatile TendenciasResponse vigente;
    private volatile Instant ultimoFallo;
    private volatile ApiException ultimoError;

    TendenciasService(ProveedorYoutube proveedor, CatalogoService catalogo, MetadatosCancionService metadatos,
                      YoutubeProperties propiedades, Clock reloj,
                      @Value("${app.trends.cache-hours:6}") int horasCache,
                      @Value("${app.trends.max-stale-hours:48}") int horasViejas,
                      @Value("${app.trends.per-minute-per-address:600}") int porMinuto) {
        if (horasCache <= 0) {
            throw new IllegalStateException("app.trends.cache-hours must be positive");
        }
        if (horasViejas < horasCache) {
            throw new IllegalStateException("app.trends.max-stale-hours must not be lower than app.trends.cache-hours");
        }
        this.proveedor = proveedor;
        this.catalogo = catalogo;
        this.metadatos = metadatos;
        this.region = propiedades.regionCode();
        this.reloj = reloj;
        this.vigencia = Duration.ofHours(horasCache);
        this.maximaAntiguedad = Duration.ofHours(horasViejas);
        this.esperaMaxima = Duration.ofSeconds(2L * propiedades.timeoutSeconds()).plus(MARGEN);
        this.limitador = new LimitadorPorDireccion(porMinuto, "app.trends.per-minute-per-address",
                "Too many requests from this device: at most " + porMinuto + " per minute. Try again later");
    }

    /**
     * The current chart, refreshing it when it is older than the cache window.
     *
     * @param direccion the client address, only as a key of the per-minute limit
     */
    public TendenciasResponse obtener(String direccion) {
        limitador.registrar(direccion);
        Instant ahora = reloj.instant();
        TendenciasResponse actual = vigente;
        if (actual != null && fresca(actual, ahora)) {
            return actual;
        }
        Instant fallo = ultimoFallo;
        ApiException error = ultimoError;
        if (fallo != null && error != null && ahora.isBefore(fallo.plus(ESPERA_TRAS_FALLO))) {
            return viejaO(actual, ahora, error);
        }
        try {
            return refrescar();
        } catch (ApiException e) {
            return viejaO(actual, reloj.instant(), e);
        }
    }

    private boolean fresca(TendenciasResponse respuesta, Instant ahora) {
        return ahora.isBefore(respuesta.actualizadoAt().plus(vigencia));
    }

    private TendenciasResponse viejaO(TendenciasResponse actual, Instant ahora, ApiException error) {
        if (actual != null && !ahora.isAfter(actual.actualizadoAt().plus(maximaAntiguedad))) {
            return actual;
        }
        throw error;
    }

    /** Leads the refresh, or waits for the one in flight. Throws the catalog error as an {@link ApiException}. */
    private TendenciasResponse refrescar() {
        CompletableFuture<TendenciasResponse> propio = new CompletableFuture<>();
        CompletableFuture<TendenciasResponse> ajeno = enCurso.compareAndExchange(null, propio);
        if (ajeno != null) {
            return esperar(ajeno);
        }
        try {
            // A flight that ended just before this one already refreshed the chart: no second call.
            TendenciasResponse actual = vigente;
            if (actual != null && fresca(actual, reloj.instant())) {
                propio.complete(actual);
                return actual;
            }
            TendenciasResponse nueva = consultar();
            vigente = nueva;
            ultimoFallo = null;
            ultimoError = null;
            propio.complete(nueva);
            return nueva;
        } catch (RuntimeException e) {
            ApiException error = comoApi(e);
            ultimoError = error;
            ultimoFallo = reloj.instant();
            propio.completeExceptionally(error);
            throw error;
        } finally {
            enCurso.compareAndSet(propio, null);
        }
    }

    private TendenciasResponse consultar() {
        List<VideoYoutube> videos = proveedor.masPopulares(region, MAXIMO).stream()
                .filter(VideoYoutube::disponible)
                .toList();
        Instant obtenida = reloj.instant();
        if (!videos.isEmpty()) {
            try {
                metadatos.registrarTodos(videos);
            } catch (DataAccessException e) {
                log.warn("Trending songs not stored ({}): the chart is served anyway", e.getClass().getSimpleName());
            }
        }
        return new TendenciasResponse(region, obtenida, catalogo.canciones(videos));
    }

    private TendenciasResponse esperar(CompletableFuture<TendenciasResponse> vuelo) {
        try {
            return vuelo.get(esperaMaxima.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw comoApi(e.getCause());
        } catch (TimeoutException e) {
            throw new YoutubeException(Tipo.UNAVAILABLE).aApi();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YoutubeException(Tipo.UNAVAILABLE).aApi();
        }
    }

    private static ApiException comoApi(Throwable e) {
        if (e instanceof ApiException api) {
            return api;
        }
        if (e instanceof YoutubeException youtube) {
            return youtube.aApi();
        }
        log.warn("Trending songs refresh failed: {}", e == null ? "unknown" : e.getClass().getSimpleName());
        return new YoutubeException(Tipo.UNAVAILABLE).aApi();
    }

    /** Forgets the chart, the last failure and the per-address windows. Only for tests. */
    void reiniciar() {
        vigente = null;
        ultimoFallo = null;
        ultimoError = null;
        limitador.reiniciar();
    }
}
