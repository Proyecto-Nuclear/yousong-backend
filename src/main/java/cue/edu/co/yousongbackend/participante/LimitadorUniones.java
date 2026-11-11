package cue.edu.co.yousongbackend.participante;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limits how many anonymous joins the public QR endpoint accepts per minute: per client address, so one
 * scripted client cannot fill the tables of an open session, and per establishment, so many addresses
 * together cannot either. Each join creates a device, a participation, consents and a credential, and the
 * session close waits for every in-flight join, so the limit is checked before any row or lock is taken.
 *
 * <p>The address is {@link jakarta.servlet.http.HttpServletRequest#getRemoteAddr()}. This class never reads
 * {@code X-Forwarded-For}: a client can forge that header. Behind a reverse proxy the operator must enable
 * {@code server.forward-headers-strategy} (and trust only the proxy), so the remote address is the real
 * client; otherwise every client shares the proxy's address and the per-address limit becomes a global one.
 * The address lives only as a key of this in-memory window: it is never stored in the database, logged or
 * echoed in an error message.
 *
 * <p>Fixed windows of one minute kept in memory, like {@link LimitadorBusquedas}: they restart with the
 * application and are per instance, which is acceptable for a protection against runaway clients (the
 * per-session cap in {@link ParticipacionService} is the hard bound on stored rows).
 */
@Component
class LimitadorUniones {

    private final Cache<String, AtomicInteger> porDireccion = ventanas(100_000);
    private final Cache<UUID, AtomicInteger> porEstablecimiento = ventanas(10_000);
    private final int maximoPorDireccion;
    private final int maximoPorEstablecimiento;

    LimitadorUniones(@Value("${app.participante.join-per-minute-per-address:10}") int maximoPorDireccion,
                     @Value("${app.participante.join-per-minute-per-establishment:120}") int maximoPorEstablecimiento) {
        if (maximoPorDireccion <= 0) {
            throw new IllegalStateException("app.participante.join-per-minute-per-address must be positive");
        }
        if (maximoPorEstablecimiento <= 0) {
            throw new IllegalStateException("app.participante.join-per-minute-per-establishment must be positive");
        }
        this.maximoPorDireccion = maximoPorDireccion;
        this.maximoPorEstablecimiento = maximoPorEstablecimiento;
    }

    private static <K> Cache<K, AtomicInteger> ventanas(long maximo) {
        // expireAfterWrite on a counter that is never replaced: the window starts with the first join.
        return Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(1)).maximumSize(maximo).build();
    }

    /**
     * Counts a join attempt from the address into the establishment; throws 429 {@code RATE_LIMITED} when
     * either minute's allowance is used. An address over its allowance does not consume the establishment's.
     */
    void registrar(String direccion, UUID establecimientoId) {
        if (contar(porDireccion, direccion) > maximoPorDireccion) {
            throw ApiException.tooManyRequests("RATE_LIMITED",
                    "Too many joins from this device: at most " + maximoPorDireccion + " per minute. Try again later");
        }
        if (contar(porEstablecimiento, establecimientoId) > maximoPorEstablecimiento) {
            throw ApiException.tooManyRequests("RATE_LIMITED",
                    "Too many people are joining right now. Try again in a moment");
        }
    }

    /** Forgets every window. Only for tests, which share one JVM and one client address. */
    void reiniciar() {
        porDireccion.invalidateAll();
        porEstablecimiento.invalidateAll();
    }

    private static <K> int contar(Cache<K, AtomicInteger> ventanas, K clave) {
        return ventanas.get(clave, ignorada -> new AtomicInteger()).incrementAndGet();
    }
}
