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
 * Limits how many catalog searches one participant can make per minute. A search can cost 100 of the
 * 10,000 daily provider quota units, so one phone must not be able to spend the quota of the whole night.
 *
 * <p>Fixed window per participation, kept in memory: it restarts with the application, which is acceptable
 * for a protection against runaway clients (the provider quota counter is the hard stop).
 */
@Component
class LimitadorBusquedas {

    private final Cache<UUID, AtomicInteger> ventanas = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(10_000)
            .build();
    private final int maximoPorMinuto;

    LimitadorBusquedas(@Value("${app.participante.search-per-minute:6}") int maximoPorMinuto) {
        this.maximoPorMinuto = maximoPorMinuto;
    }

    /** Counts a search for the participant; throws 429 {@code RATE_LIMITED} when the minute's allowance is used. */
    void registrar(UUID participacionId) {
        int usadas = ventanas.get(participacionId, id -> new AtomicInteger()).incrementAndGet();
        if (usadas > maximoPorMinuto) {
            throw ApiException.tooManyRequests("RATE_LIMITED",
                    "Too many searches: at most " + maximoPorMinuto + " per minute. Try again in a moment");
        }
    }
}
