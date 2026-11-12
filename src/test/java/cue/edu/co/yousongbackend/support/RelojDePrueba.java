package cue.edu.co.yousongbackend.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A movable clock that replaces the application clock in the test classes that import this configuration (they
 * get their own Spring context, so other test classes keep the real clock). Access tokens are still validated
 * against the real time by Spring Security, so moving this clock never invalidates them.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RelojDePrueba {

    @Bean
    @Primary
    RelojMovible relojMovible() {
        return new RelojMovible(Instant.now());
    }

    /** A UTC clock that only moves when a test moves it. */
    public static final class RelojMovible extends Clock {

        private volatile Instant ahora;

        RelojMovible(Instant inicial) {
            this.ahora = inicial;
        }

        public void fijar(Instant instante) {
            this.ahora = instante;
        }

        public void avanzar(Duration duracion) {
            this.ahora = ahora.plus(duracion);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(ahora, zone);
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }
}
