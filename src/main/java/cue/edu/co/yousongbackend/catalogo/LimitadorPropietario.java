package cue.edu.co.yousongbackend.catalogo;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limits how many catalog searches and suggestions an establishment owner can ask for per minute, keyed by the
 * establishment. Creating an establishment is self-service, so a script owning one must not be able to burn its
 * daily provider budget ({@link CuotaYoutube}) in seconds, nor hammer the suggestion endpoint. Only the catalog
 * routes are limited: the owner's configuration routes are not.
 *
 * <p>Fixed windows of one minute kept in memory, like the participant limiters: they restart with the application
 * and are per instance, which is acceptable for a protection against runaway clients (the daily budget is the
 * hard stop).
 */
@Component
class LimitadorPropietario {

    private final Cache<UUID, AtomicInteger> busquedas = ventanas();
    private final Cache<UUID, AtomicInteger> sugerencias = ventanas();
    private final int busquedasPorMinuto;
    private final int sugerenciasPorMinuto;

    LimitadorPropietario(@Value("${app.propietario.search-per-minute:10}") int busquedasPorMinuto,
                         @Value("${app.propietario.suggestions-per-minute:60}") int sugerenciasPorMinuto) {
        if (busquedasPorMinuto <= 0 || sugerenciasPorMinuto <= 0) {
            throw new IllegalStateException("app.propietario.search-per-minute and suggestions-per-minute must be positive");
        }
        this.busquedasPorMinuto = busquedasPorMinuto;
        this.sugerenciasPorMinuto = sugerenciasPorMinuto;
    }

    private static Cache<UUID, AtomicInteger> ventanas() {
        // expireAfterWrite on a counter that is never replaced: the window starts with the first call.
        return Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).maximumSize(10_000).build();
    }

    /** Counts a catalog search; throws 429 {@code RATE_LIMITED} when the minute's allowance is used. */
    void registrarBusqueda(UUID establecimientoId) {
        if (contar(busquedas, establecimientoId) > busquedasPorMinuto) {
            throw ApiException.tooManyRequests("RATE_LIMITED",
                    "Too many searches: at most " + busquedasPorMinuto + " per minute. Try again in a moment");
        }
    }

    /** Counts a suggestion request; throws 429 {@code RATE_LIMITED} when the minute's allowance is used. */
    void registrarSugerencia(UUID establecimientoId) {
        if (contar(sugerencias, establecimientoId) > sugerenciasPorMinuto) {
            throw ApiException.tooManyRequests("RATE_LIMITED",
                    "Too many suggestion requests: at most " + sugerenciasPorMinuto + " per minute. Try again in a moment");
        }
    }

    private static int contar(Cache<UUID, AtomicInteger> ventanas, UUID clave) {
        return ventanas.get(clave, ignorada -> new AtomicInteger()).incrementAndGet();
    }
}
