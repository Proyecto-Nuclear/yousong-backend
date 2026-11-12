package cue.edu.co.yousongbackend.establecimiento;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limits how many establishments one client address may try to create per hour
 * ({@code app.propietario.create-per-hour-per-address}). Every establishment gets its own daily YouTube budget, so
 * a script registering many accounts must not mint budgets for free. Checked before any row or lock is taken.
 *
 * <p>The address is {@link jakarta.servlet.http.HttpServletRequest#getRemoteAddr()}, never {@code X-Forwarded-For}
 * (a client can forge it); behind a reverse proxy the operator must enable {@code server.forward-headers-strategy},
 * exactly as for the participant join limiter. The address lives only as a key of this in-memory window: it is
 * never stored in the database, logged or echoed in an error message.
 *
 * <p>A fixed window of one hour kept in memory: it restarts with the application and is per instance, which is
 * acceptable for a brake on scripted registrations (the owner share of the quota is the hard bound).
 */
@Component
class LimitadorCreaciones {

    private final Cache<String, AtomicInteger> porDireccion = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .maximumSize(100_000)
            .build();
    private final int maximoPorHora;

    LimitadorCreaciones(@Value("${app.propietario.create-per-hour-per-address:5}") int maximoPorHora) {
        if (maximoPorHora <= 0) {
            throw new IllegalStateException("app.propietario.create-per-hour-per-address must be positive");
        }
        this.maximoPorHora = maximoPorHora;
    }

    /** Counts a creation attempt from the address; throws 429 {@code RATE_LIMITED} when the hour's allowance is used. */
    void registrar(String direccion) {
        int intentos = porDireccion.get(direccion, ignorada -> new AtomicInteger()).incrementAndGet();
        if (intentos > maximoPorHora) {
            throw ApiException.tooManyRequests("RATE_LIMITED",
                    "Too many establishments created from this device: at most " + maximoPorHora
                            + " per hour. Try again later");
        }
    }
}
