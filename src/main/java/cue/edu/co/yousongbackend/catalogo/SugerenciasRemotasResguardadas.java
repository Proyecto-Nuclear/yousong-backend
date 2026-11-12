package cue.edu.co.yousongbackend.catalogo;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Puts the unofficial suggest source behind a switch, a cache, an outbound throttle and a circuit breaker,
 * and NEVER throws: any problem means "no remote suggestions" for that request.
 *
 * <ul>
 *   <li>Cache: the answer per lowercase query (successes only), so typing back and forth costs nothing.</li>
 *   <li>Throttle: at most {@code max-calls-per-second} outgoing calls per clock second; over it the remote is
 *       skipped for that request instead of queueing.</li>
 *   <li>Circuit breaker: after {@code failure-threshold} consecutive failures no call is made for
 *       {@code cooldown-minutes}; then one call is tried again, and a failure opens it again at once.</li>
 * </ul>
 * Nothing about the query is logged: only the exception class or a fixed description.
 */
@Slf4j
@Component
class SugerenciasRemotasResguardadas {

    private final SugerenciasRemotas remotas;
    private final boolean activa;
    private final int maximoPorSegundo;
    private final int umbralFallos;
    private final Duration enfriamiento;
    private final Clock reloj;
    private final Cache<String, List<String>> cache;

    private long segundoActual = Long.MIN_VALUE;
    private int llamadasEnSegundo;
    private int fallosSeguidos;
    private Instant abiertoHasta = Instant.MIN;

    SugerenciasRemotasResguardadas(SugerenciasRemotas remotas, SugerenciasProperties propiedades, Clock reloj) {
        this.remotas = remotas;
        this.activa = propiedades.enabled();
        this.maximoPorSegundo = propiedades.maxCallsPerSecond();
        this.umbralFallos = propiedades.failureThreshold();
        this.enfriamiento = Duration.ofMinutes(propiedades.cooldownMinutes());
        this.reloj = reloj;
        this.cache = Caffeine.newBuilder()
                .maximumSize(2_000)
                .expireAfterWrite(Duration.ofMinutes(propiedades.cacheMinutes()))
                .build();
    }

    /** Remote search strings for a normalized query, or an empty list when the remote is off or not usable. */
    List<String> sugerir(String consulta) {
        if (!activa) {
            return List.of();
        }
        String clave = consulta.toLowerCase(Locale.ROOT);
        List<String> guardadas = cache.getIfPresent(clave);
        if (guardadas != null) {
            return guardadas;
        }
        if (!permitirLlamada()) {
            return List.of();
        }
        try {
            List<String> sugeridas = List.copyOf(remotas.sugerir(consulta));
            registrarExito();
            cache.put(clave, sugeridas);
            return sugeridas;
        } catch (RuntimeException e) {
            // The adapter already logged what failed; anything else is logged by class only.
            if (!(e instanceof SugerenciasRemotasException)) {
                log.warn("YouTube suggest source failed: {}", e.getClass().getSimpleName());
            }
            registrarFallo();
            return List.of();
        }
    }

    /** Circuit closed (or cooldown over) and under the per-second limit; counts the call when allowed. */
    private synchronized boolean permitirLlamada() {
        Instant ahora = reloj.instant();
        if (ahora.isBefore(abiertoHasta)) {
            return false;
        }
        long segundo = ahora.getEpochSecond();
        if (segundo != segundoActual) {
            segundoActual = segundo;
            llamadasEnSegundo = 0;
        }
        if (llamadasEnSegundo >= maximoPorSegundo) {
            return false;
        }
        llamadasEnSegundo++;
        return true;
    }

    private synchronized void registrarExito() {
        fallosSeguidos = 0;
        abiertoHasta = Instant.MIN;
    }

    private synchronized void registrarFallo() {
        fallosSeguidos++;
        if (fallosSeguidos >= umbralFallos) {
            abiertoHasta = reloj.instant().plus(enfriamiento);
            log.warn("YouTube suggest source disabled for {} minutes after {} consecutive failures",
                    enfriamiento.toMinutes(), fallosSeguidos);
        }
    }
}
