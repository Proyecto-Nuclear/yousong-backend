package cue.edu.co.yousongbackend.catalogo;

import cue.edu.co.yousongbackend.catalogo.YoutubeException.Tipo;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/**
 * Makes sure a song someone wants to request or block is in the catalog, looking it up at YouTube when it is
 * not: {@link MetadatosCancionService#consultarParaSolicitud} followed by {@link MetadatosCancionService#registrar}.
 *
 * <p>Single flight per video id, in this instance: callers asking for the same unknown video at the same time
 * share ONE lookup, so a crowd requesting a new song spends one videos.list unit instead of one per caller. The
 * first caller leads it, and the unit is charged to the leader's {@code origen} (its establishment's daily
 * budget, participant or owner); the others wait for its outcome and get the same answer or the same error
 * (404 {@code SONG_NOT_FOUND}, 409 {@code SONG_UNAVAILABLE}, 503/429 catalog errors). Nothing is remembered
 * after a failure: the flight is removed when it ends, so the next caller performs a fresh lookup, and a failed
 * provider call is refunded once by {@link ProveedorYoutube}.
 *
 * <p>No transaction is held while waiting (callers run this before opening theirs), and a wait never outlasts
 * the provider timeout (connect plus read) and a short registration margin: past it the waiter gets 503
 * {@code CATALOG_UNAVAILABLE} while the leader carries on.
 */
@Component
public class RegistroCancionSolicitada {

    private static final Duration MARGEN_REGISTRO = Duration.ofSeconds(10);

    private final MetadatosCancionService metadatos;
    private final CancionRepository canciones;
    private final Duration esperaMaxima;
    private final ConcurrentMap<String, CompletableFuture<Void>> enCurso = new ConcurrentHashMap<>();

    RegistroCancionSolicitada(MetadatosCancionService metadatos, CancionRepository canciones,
                              YoutubeProperties propiedades) {
        this.metadatos = metadatos;
        this.canciones = canciones;
        this.esperaMaxima = Duration.ofSeconds(2L * propiedades.timeoutSeconds()).plus(MARGEN_REGISTRO);
    }

    /**
     * Returns once the video is stored in the catalog, looking it up (charged to {@code origen}) when it is not.
     *
     * @throws cue.edu.co.yousongbackend.shared.web.ApiException the errors of
     *         {@link MetadatosCancionService#consultarParaSolicitud}, shared by every concurrent caller
     */
    public void asegurar(String videoId, OrigenBusqueda origen) {
        if (guardada(videoId)) {
            return;
        }
        CompletableFuture<Void> propio = new CompletableFuture<>();
        CompletableFuture<Void> ajeno = enCurso.putIfAbsent(videoId, propio);
        if (ajeno != null) {
            esperar(ajeno);
            return;
        }
        try {
            // A flight that ended just before this one registered the song: no second lookup.
            if (!guardada(videoId)) {
                metadatos.registrar(metadatos.consultarParaSolicitud(videoId, origen));
            }
            propio.complete(null);
        } catch (RuntimeException | Error e) {
            propio.completeExceptionally(e);
            throw e;
        } finally {
            enCurso.remove(videoId, propio);
        }
    }

    private boolean guardada(String videoId) {
        return !canciones.findByYoutubeVideoIdIn(List.of(videoId)).isEmpty();
    }

    private void esperar(CompletableFuture<Void> vuelo) {
        try {
            vuelo.get(esperaMaxima.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException causa) {
                throw causa;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new YoutubeException(Tipo.UNAVAILABLE).aApi();
        } catch (TimeoutException e) {
            throw new YoutubeException(Tipo.UNAVAILABLE).aApi();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new YoutubeException(Tipo.UNAVAILABLE).aApi();
        }
    }
}
