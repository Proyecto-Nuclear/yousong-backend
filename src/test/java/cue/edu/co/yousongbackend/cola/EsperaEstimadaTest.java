package cue.edu.co.yousongbackend.cola;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;

class EsperaEstimadaTest {

    private static final Instant INICIO = Instant.parse("2026-11-11T22:00:00Z");

    @Test
    void theRemainingTimeIsTheOperatingDurationMinusTheElapsedSeconds() {
        assertThat(EsperaEstimada.restante(INICIO, 200, INICIO.plusSeconds(50))).isEqualTo(150);
    }

    @Test
    void theRemainingTimeNeverGoesBelowZero() {
        assertThat(EsperaEstimada.restante(INICIO, 200, INICIO.plusSeconds(500))).isZero();
    }

    @Test
    void aPlaybackNotStartedYetStillHasItsWholeDuration() {
        assertThat(EsperaEstimada.restante(null, 180, INICIO)).isEqualTo(180);
    }

    @Test
    void aClockBehindTheStartDoesNotAddTime() {
        assertThat(EsperaEstimada.restante(INICIO, 200, INICIO.minusSeconds(30))).isEqualTo(200);
    }

    @ParameterizedTest
    @NullSource
    void aMissingDurationUsesTheInitialReferenceOf240Seconds(Integer duracion) {
        assertThat(EsperaEstimada.duracion(duracion)).isEqualTo(240);
    }

    @ParameterizedTest
    @CsvSource({"0, 240", "-5, 240", "1, 1", "185, 185"})
    void onlyAPositiveDurationIsTrusted(int duracion, int esperada) {
        assertThat(EsperaEstimada.duracion(duracion)).isEqualTo(esperada);
    }

    @Test
    void eachItemWaitsForThePlayingSongAndEveryItemAhead() {
        assertThat(EsperaEstimada.esperas(100, Arrays.asList(200, null, 0, 60))).containsExactly(100L, 300L, 540L, 780L);
    }

    @Test
    void anEmptyQueueHasNoWaits() {
        assertThat(EsperaEstimada.esperas(100, List.of())).isEmpty();
    }
}
