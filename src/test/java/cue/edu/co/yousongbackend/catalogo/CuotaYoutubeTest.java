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
        return cuota(limite, share, presupuesto, 0.25, 1000);
    }

    private CuotaYoutube cuota(int limite, double share, int presupuesto, double ownerShare, int ownerBudget) {
        YoutubeProperties propiedades = new YoutubeProperties("k", "http://localhost", 1, "CO", "es", 5, limite, 7, 60);
        return new CuotaYoutube(propiedades, reloj, share, presupuesto, ownerShare, ownerBudget);
    }

    private static OrigenBusqueda propietario(UUID establecimientoId) {
        return OrigenBusqueda.propietario(establecimientoId);
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
    @Test
    void aRefundGivesBackTheUnitsToEveryCounter() {
        CuotaYoutube cuota = cuota(1000, 0.5, BUSQUEDA);
        var dia = cuota.reservar(LOCAL_A, BUSQUEDA);
        assertThat(rechazo(() -> cuota.reservar(LOCAL_A, 1))).isEqualTo(YoutubeException.Tipo.BUDGET_EXHAUSTED);

        cuota.devolver(LOCAL_A, dia, BUSQUEDA);

        assertThat(cuota.usadas()).isZero();
        assertThat(rechazo(() -> cuota.reservar(LOCAL_A, BUSQUEDA))).isNull();
    }

    @Test
    void aRefundNeverGoesBelowZeroNorCrossesTheMidnightReset() {
        CuotaYoutube cuota = cuota(1000, 0.5, 100_000);
        var dia = cuota.reservar(LOCAL_A, BUSQUEDA);
        reloj.ahora = reloj.ahora.plusSeconds(2 * 24 * 3600);
        cuota.reservar(LOCAL_A, BUSQUEDA);

        cuota.devolver(LOCAL_A, dia, BUSQUEDA);

        assertThat(cuota.usadas()).as("the refund belongs to the previous day").isEqualTo(BUSQUEDA);
        cuota.devolver(LOCAL_A, cuota.reservar(LOCAL_B, 1), 5_000);
        assertThat(cuota.usadas()).isZero();
    }

    @Test
    void ownersHaveADailyBudgetPerEstablishmentSeparateFromTheirPatrons() {
        CuotaYoutube cuota = cuota(1_000_000, 0.7, 2 * BUSQUEDA, 0.25, 2 * BUSQUEDA);
        cuota.reservar(propietario(LOCAL_A), BUSQUEDA);
        cuota.reservar(propietario(LOCAL_A), BUSQUEDA);

        assertThatThrownBy(() -> cuota.reservar(propietario(LOCAL_A), BUSQUEDA))
                .isInstanceOfSatisfying(YoutubeException.class, e -> {
                    assertThat(e.aApi().status().value()).isEqualTo(429);
                    assertThat(e.aApi().code()).isEqualTo("SEARCH_BUDGET_EXHAUSTED");
                });
        assertThat(rechazo(() -> cuota.reservar(LOCAL_A, BUSQUEDA))).as("the patrons of that place").isNull();
        assertThat(rechazo(() -> cuota.reservar(LOCAL_A, BUSQUEDA))).isNull();
        assertThat(rechazo(() -> cuota.reservar(LOCAL_A, BUSQUEDA))).as("their own budget is separate")
                .isEqualTo(YoutubeException.Tipo.BUDGET_EXHAUSTED);
        assertThat(rechazo(() -> cuota.reservar(propietario(LOCAL_B), BUSQUEDA))).as("another owner").isNull();
        assertThat(cuota.usadas()).as("owner spend counts toward the total").isEqualTo(5 * BUSQUEDA);
    }

    @Test
    void ownersTogetherMaySpendOnlyTheOwnerShareAndParticipantsKeepTheirs() {
        CuotaYoutube cuota = cuota(1000, 0.5, 100_000, 0.25, 100_000);
        cuota.reservar(propietario(UUID.randomUUID()), BUSQUEDA);
        cuota.reservar(propietario(UUID.randomUUID()), BUSQUEDA);

        assertThat(rechazo(() -> cuota.reservar(propietario(UUID.randomUUID()), BUSQUEDA)))
                .as("202 + 101 would pass the owner share of 250")
                .isEqualTo(YoutubeException.Tipo.QUOTA_EXCEEDED);
        for (int i = 0; i < 4; i++) {
            assertThat(rechazo(() -> cuota.reservar(UUID.randomUUID(), BUSQUEDA))).as("participant " + i).isNull();
        }
        assertThat(cuota.disponible()).as("a reserve stays for the system").isTrue();
    }

    @Test
    void anOwnerRefundGivesBackTheUnitsToTheOwnerCounters() {
        CuotaYoutube cuota = cuota(1000, 0.5, 100_000, 0.25, BUSQUEDA);
        var dia = cuota.reservar(propietario(LOCAL_A), BUSQUEDA);
        assertThat(rechazo(() -> cuota.reservar(propietario(LOCAL_A), 1))).isNotNull();

        cuota.devolver(propietario(LOCAL_A), dia, BUSQUEDA);

        assertThat(cuota.usadas()).isZero();
        assertThat(rechazo(() -> cuota.reservar(propietario(LOCAL_A), BUSQUEDA))).isNull();
    }

    @Test
    void ownerCountersResetAtPacificMidnight() {
        CuotaYoutube cuota = cuota(1000, 0.5, 100_000, 0.25, BUSQUEDA);
        cuota.reservar(propietario(LOCAL_A), BUSQUEDA);
        assertThat(rechazo(() -> cuota.reservar(propietario(LOCAL_A), BUSQUEDA))).isNotNull();

        reloj.ahora = Instant.parse("2026-11-12T08:00:01Z");

        assertThat(rechazo(() -> cuota.reservar(propietario(LOCAL_A), BUSQUEDA))).isNull();
    }
}
