package cue.edu.co.yousongbackend.cola;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Limits how many queue actions (requests and votes together) one participant can make per minute, so a
 * runaway client cannot flood the queue or hold the session lock that queue changes take.
 *
 * <p>Fixed window per participation, kept in memory, like the search limiter: it restarts with the application,
 * which is acceptable for a protection against runaway clients. Checked before any other work.
 */
@Component
class LimitadorAccionesCola {

    private final Cache<UUID, AtomicInteger> ventanas = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(10_000)
            .build();
    private final int maximoPorMinuto;

    LimitadorAccionesCola(@Value("${app.participante.queue-actions-per-minute:10}") int maximoPorMinuto) {
        if (maximoPorMinuto <= 0) {
            throw new IllegalStateException("app.participante.queue-actions-per-minute must be positive");
        }
        this.maximoPorMinuto = maximoPorMinuto;
    }

    /** Counts an action of the participant; throws 429 {@code RATE_LIMITED} when the minute's allowance is used. */
    void registrar(UUID participacionId) {
        int usadas = ventanas.get(participacionId, id -> new AtomicInteger()).incrementAndGet();
        if (usadas > maximoPorMinuto) {
            throw ApiException.tooManyRequests("RATE_LIMITED",
                    "Too many requests and votes: at most " + maximoPorMinuto + " per minute. Try again in a moment");
        }
    }
}
