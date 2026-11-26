package cue.edu.co.yousongbackend.shared.web;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A per-minute allowance per client address for an anonymous read endpoint. Each endpoint creates its own
 * instance, so each has its own counters.
 *
 * <p>The address is {@link jakarta.servlet.http.HttpServletRequest#getRemoteAddr()}, like the join limiter: never
 * {@code X-Forwarded-For}, which a client can forge (behind a reverse proxy the operator enables
 * {@code server.forward-headers-strategy} and trusts only the proxy). The address lives only as a key of this
 * in-memory window: it is never stored, logged or echoed in an error message. Fixed windows of one minute that
 * start with the first call, per instance and lost on restart: a protection against runaway clients, not a quota.
 */
public final class LimitadorPorDireccion {

    private final Cache<String, AtomicInteger> ventanas = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(100_000)
            .build();
    private final int maximo;
    private final String mensaje;

    /**
     * @param maximo  calls allowed per address and minute (must be positive)
     * @param ajuste  the name of the setting it comes from, for the startup error
     * @param mensaje what a limited client is told
     */
    public LimitadorPorDireccion(int maximo, String ajuste, String mensaje) {
        if (maximo <= 0) {
            throw new IllegalStateException(ajuste + " must be positive");
        }
        this.maximo = maximo;
        this.mensaje = mensaje;
    }

    /** Counts one call from the address; throws 429 {@code RATE_LIMITED} once the minute's allowance is used. */
    public void registrar(String direccion) {
        String clave = direccion == null ? "" : direccion;
        if (ventanas.get(clave, ignorada -> new AtomicInteger()).incrementAndGet() > maximo) {
            throw ApiException.tooManyRequests("RATE_LIMITED", mensaje);
        }
    }

    /** Forgets every window. Only for tests, which share one JVM and one client address. */
    public void reiniciar() {
        ventanas.invalidateAll();
    }
}
