package cue.edu.co.yousongbackend.sesion;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The operating date is the local calendar date of the establishment when its session opens. */
class FechaOperativaTest {

    @Test
    void usesTheLocalDateOfTheEstablishmentNotUtc() {
        // 03:00 UTC is still the previous evening in Bogota (UTC-5): a night session belongs to that day.
        Instant opening = Instant.parse("2026-11-09T03:00:00Z");
        assertThat(FechaOperativa.de(opening, "America/Bogota")).isEqualTo(LocalDate.of(2026, 11, 8));
    }

    @Test
    void switchesAtLocalMidnight() {
        assertThat(FechaOperativa.de(Instant.parse("2026-11-09T04:59:59Z"), "America/Bogota"))
                .isEqualTo(LocalDate.of(2026, 11, 8));
        assertThat(FechaOperativa.de(Instant.parse("2026-11-09T05:00:00Z"), "America/Bogota"))
                .isEqualTo(LocalDate.of(2026, 11, 9));
    }

    @Test
    void followsTheZoneOfEachEstablishment() {
        Instant opening = Instant.parse("2026-11-09T23:30:00Z");
        assertThat(FechaOperativa.de(opening, "America/Bogota")).isEqualTo(LocalDate.of(2026, 11, 9));
        assertThat(FechaOperativa.de(opening, "Europe/Madrid")).isEqualTo(LocalDate.of(2026, 11, 10));
    }
}
