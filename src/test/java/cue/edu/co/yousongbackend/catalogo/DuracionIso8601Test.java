package cue.edu.co.yousongbackend.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DuracionIso8601Test {

    @ParameterizedTest
    @CsvSource({
            "PT3M47S, 227",
            "PT1H2M3S, 3723",
            "PT45S, 45",
            "PT10M, 600",
            "PT2H, 7200",
            "P0D, 0",
            "P1DT2H, 93600",
            "P1D, 86400",
            "PT0S, 0",
            "PT59M59S, 3599"
    })
    void convertsToSeconds(String iso, int seconds) {
        assertThat(DuracionIso8601.segundos(iso)).isEqualTo(seconds);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"P", "PT", "3:47", "PT3M47", "abc", "PT-3S", "PT99999999999999999999S"})
    void answersNullWhenItIsNotADuration(String iso) {
        assertThat(DuracionIso8601.segundos(iso)).isNull();
    }

    @Test
    void ignoresSurroundingWhitespace() {
        assertThat(DuracionIso8601.segundos("  PT1M ")).isEqualTo(60);
    }
}
