package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The daily quota split between staff and participants, and the per-establishment search budget. */
class CuotaYoutubeTest {

    private static final int BUSQUEDA = CuotaYoutube.COSTO_BUSQUEDA + CuotaYoutube.COSTO_DETALLES;
    private static final UUID LOCAL_A = UUID.randomUUID();
    private static final UUID LOCAL_B = UUID.randomUUID();

    /** A clock the test can move forward. */
    private static final class RelojMovible extends Clock {
        private Instant ahora = Instant.parse("2026-11-11T12:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zona) {
            return Clock.fixed(ahora, zona);
        }

        @Override
        public Instant instant() {
            return ahora;
        }
    }

    private final RelojMovible reloj = new RelojMovible();

    private CuotaYoutube cuota(int limite, double share, int presupuesto) {
        YoutubeProperties propiedades = new YoutubeProperties("k", "http://localhost", 1, "CO", "es", 5, limite, 7, 60);
        return new CuotaYoutube(propiedades, reloj, share, presupuesto);
    }

    private static YoutubeException.Tipo rechazo(Runnable accion) {
        try {
            accion.run();
        } catch (YoutubeException e) {
            return e.tipo();
        }
        return null;
    }

    @Test
    void participantsMaySpendOnlyTheirShareOfTheDailyLimit() {
        CuotaYoutube cuota = cuota(1000, 0.5, 100_000);
        for (int i = 0; i < 4; i++) {
            cuota.reservar(UUID.randomUUID(), BUSQUEDA);
        }

        assertThat(rechazo(() -> cuota.reservar(UUID.randomUUID(), BUSQUEDA)))
                .as("404 + 101 would pass the participant share of 500")
                .isEqualTo(YoutubeException.Tipo.QUOTA_EXCEEDED);
        assertThat(cuota.disponible()).as("staff keeps the reserve").isTrue();
        assertThat(cuota.usadas()).as("participant spend counts toward the total").isEqualTo(404);
    }

    @Test
    void staffSpendLeavesParticipantsWithLessRoom() {
        CuotaYoutube cuota = cuota(1000, 0.7, 100_000);
        cuota.consumir(900);

        assertThat(rechazo(() -> cuota.reservar(LOCAL_A, BUSQUEDA))).isEqualTo(YoutubeException.Tipo.QUOTA_EXCEEDED);
        assertThat(cuota.usadas()).isEqualTo(900);
    }

    @Test
    void eachEstablishmentHasItsOwnDailyBudget() {
        CuotaYoutube cuota = cuota(1_000_000, 0.7, 250);
        cuota.reservar(LOCAL_A, BUSQUEDA);
        cuota.reservar(LOCAL_A, BUSQUEDA);

        assertThat(rechazo(() -> cuota.reservar(LOCAL_A, BUSQUEDA))).isEqualTo(YoutubeException.Tipo.BUDGET_EXHAUSTED);
        assertThat(rechazo(() -> cuota.reservar(LOCAL_B, BUSQUEDA))).as("another establishment is not affected").isNull();
        assertThat(cuota.usadas()).as("a refused reservation charges nothing").isEqualTo(3 * BUSQUEDA);
    }

    @Test
    void theBudgetIsMappedToTooManyRequests() {
        CuotaYoutube cuota = cuota(1_000_000, 0.7, 50);

        assertThatThrownBy(() -> cuota.reservar(LOCAL_A, BUSQUEDA))
                .isInstanceOfSatisfying(YoutubeException.class, e -> {
                    assertThat(e.aApi().status().value()).isEqualTo(429);
                    assertThat(e.aApi().code()).isEqualTo("SEARCH_BUDGET_EXHAUSTED");
                });
    }

    @Test
    void everyCounterResetsAtPacificMidnight() {
        CuotaYoutube cuota = cuota(1000, 0.5, 250);
        cuota.reservar(LOCAL_A, BUSQUEDA);
        cuota.reservar(LOCAL_A, BUSQUEDA);
        cuota.reservar(LOCAL_B, BUSQUEDA);
        cuota.reservar(LOCAL_B, BUSQUEDA);
        assertThat(rechazo(() -> cuota.reservar(LOCAL_A, BUSQUEDA))).isNotNull();

        reloj.ahora = Instant.parse("2026-11-12T08:00:01Z"); // 00:00:01 in Los Angeles

        assertThat(cuota.usadas()).isZero();
        assertThat(rechazo(() -> cuota.reservar(LOCAL_A, BUSQUEDA))).isNull();
        assertThat(rechazo(() -> cuota.reservar(LOCAL_A, BUSQUEDA))).isNull();
    }
}
