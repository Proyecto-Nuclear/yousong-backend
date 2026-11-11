package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * The guard around the unofficial suggest source: cache, outbound throttle, circuit breaker and switch. It
 * never throws, so a broken remote can never fail the suggestions endpoint. Time is a mutable clock.
 */
class SugerenciasRemotasResguardadasTest {

    private static final class RelojMovible extends Clock {
        private Instant ahora = Instant.parse("2026-11-11T12:00:00.250Z");

        void avanzar(Duration duracion) {
            ahora = ahora.plus(duracion);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }

    /** A scripted remote that counts its calls. */
    private static final class RemotaFalsa implements SugerenciasRemotas {
        private final AtomicInteger llamadas = new AtomicInteger();
        private volatile boolean falla;

        @Override
        public List<String> sugerir(String consulta) {
            llamadas.incrementAndGet();
            if (falla) {
                throw new SugerenciasRemotasException("scripted");
            }
            return List.of(consulta + " live", consulta + " letra");
        }
    }

    private final RelojMovible reloj = new RelojMovible();
    private final RemotaFalsa remota = new RemotaFalsa();

    private SugerenciasRemotasResguardadas guarda(boolean activa, int porSegundo, int umbral, int enfriamientoMin) {
        SugerenciasProperties propiedades = new SugerenciasProperties(activa, "http://unused.test", 1500, 60,
                porSegundo, umbral, enfriamientoMin);
        return new SugerenciasRemotasResguardadas(remota, propiedades, reloj);
    }

    private SugerenciasRemotasResguardadas guarda() {
        return guarda(true, 100, 3, 10);
    }

    @Test
    void returnsWhatTheRemoteSuggests() {
        assertThat(guarda().sugerir("shakira")).containsExactly("shakira live", "shakira letra");
    }

    @Test
    void anIdenticalQueryIsAnsweredFromTheCacheIgnoringCase() {
        SugerenciasRemotasResguardadas guarda = guarda();

        guarda.sugerir("Shakira");
        List<String> segunda = guarda.sugerir("shakira");

        assertThat(remota.llamadas).hasValue(1);
        assertThat(segunda).containsExactly("Shakira live", "Shakira letra");
    }

    @Test
    void aFailureGivesNoSuggestionsNeverThrowsAndIsNotCached() {
        SugerenciasRemotasResguardadas guarda = guarda();
        remota.falla = true;

        assertThat(guarda.sugerir("shakira")).isEmpty();
        remota.falla = false;

        assertThat(guarda.sugerir("shakira")).isNotEmpty();
        assertThat(remota.llamadas).hasValue(2);
    }

    @Test
    void theCircuitOpensAfterTheThresholdAndClosesAfterTheCooldown() {
        SugerenciasRemotasResguardadas guarda = guarda(true, 100, 3, 10);
        remota.falla = true;
        guarda.sugerir("uno");
        guarda.sugerir("dos");
        guarda.sugerir("tres");
        assertThat(remota.llamadas).hasValue(3);

        assertThat(guarda.sugerir("cuatro")).isEmpty();
        reloj.avanzar(Duration.ofMinutes(10).minusSeconds(1));
        assertThat(guarda.sugerir("cinco")).isEmpty();
        assertThat(remota.llamadas).as("no call while the circuit is open").hasValue(3);

        remota.falla = false;
        reloj.avanzar(Duration.ofSeconds(2));
        assertThat(guarda.sugerir("seis")).isNotEmpty();
        assertThat(remota.llamadas).hasValue(4);
        assertThat(guarda.sugerir("siete")).isNotEmpty();
        assertThat(remota.llamadas).as("a success closes the circuit").hasValue(5);
    }

    @Test
    void aFailedTrialAfterTheCooldownOpensTheCircuitAgain() {
        SugerenciasRemotasResguardadas guarda = guarda(true, 100, 2, 10);
        remota.falla = true;
        guarda.sugerir("uno");
        guarda.sugerir("dos");
        reloj.avanzar(Duration.ofMinutes(11));

        guarda.sugerir("tres");
        guarda.sugerir("cuatro");

        assertThat(remota.llamadas).as("one trial, then open again").hasValue(3);
    }

    @Test
    void aSuccessResetsTheConsecutiveFailures() {
        SugerenciasRemotasResguardadas guarda = guarda(true, 100, 3, 10);
        remota.falla = true;
        guarda.sugerir("uno");
        guarda.sugerir("dos");
        remota.falla = false;
        guarda.sugerir("tres");
        remota.falla = true;
        guarda.sugerir("cuatro");
        guarda.sugerir("cinco");

        guarda.sugerir("seis");

        assertThat(remota.llamadas).as("never three failures in a row before the sixth call").hasValue(6);
    }

    @Test
    void overThePerSecondLimitTheRemoteIsSkipped() {
        SugerenciasRemotasResguardadas guarda = guarda(true, 2, 3, 10);

        guarda.sugerir("uno");
        guarda.sugerir("dos");
        assertThat(guarda.sugerir("tres")).isEmpty();
        assertThat(remota.llamadas).hasValue(2);

        reloj.avanzar(Duration.ofSeconds(1));
        assertThat(guarda.sugerir("tres")).isNotEmpty();
        assertThat(remota.llamadas).hasValue(3);
    }

    @Test
    void cacheHitsDoNotCountAgainstTheThrottle() {
        SugerenciasRemotasResguardadas guarda = guarda(true, 1, 3, 10);

        guarda.sugerir("uno");

        assertThat(guarda.sugerir("uno")).isNotEmpty();
        assertThat(guarda.sugerir("UNO")).isNotEmpty();
    }

    @Test
    void switchedOffTheRemoteIsNeverCalled() {
        SugerenciasRemotasResguardadas guarda = guarda(false, 100, 3, 10);

        assertThat(guarda.sugerir("shakira")).isEmpty();
        assertThat(remota.llamadas).hasValue(0);
    }
}
