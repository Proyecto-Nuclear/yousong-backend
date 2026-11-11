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
 * Limits how many search-as-you-type requests one participant can make per minute. Suggestions spend no
 * provider quota, but each keystroke can be a request (to our database and to the unofficial suggest
 * source), so one phone must not be able to flood them.
 *
 * <p>Fixed window per participation, kept in memory, like {@link LimitadorBusquedas}.
 */
@Component
class LimitadorSugerencias {

    private final Cache<UUID, AtomicInteger> ventanas = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(10_000)
            .build();
    private final int maximoPorMinuto;

    LimitadorSugerencias(@Value("${app.participante.suggestions-per-minute:60}") int maximoPorMinuto) {
        this.maximoPorMinuto = maximoPorMinuto;
    }

    /** Counts a request for the participant; throws 429 {@code RATE_LIMITED} when the minute's allowance is used. */
    void registrar(UUID participacionId) {
        int usadas = ventanas.get(participacionId, id -> new AtomicInteger()).incrementAndGet();
        if (usadas > maximoPorMinuto) {
            throw ApiException.tooManyRequests("RATE_LIMITED",
                    "Too many suggestion requests: at most " + maximoPorMinuto + " per minute. Try again in a moment");
        }
    }
}
