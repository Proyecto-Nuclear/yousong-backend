package cue.edu.co.yousongbackend.establecimiento;

import static org.assertj.core.api.Assertions.assertThat;

import cue.edu.co.yousongbackend.establecimiento.HorarioSemanal.Ventana;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Weekly windows evaluated as wall-clock times of the establishment's zone, never as UTC arithmetic. */
class HorarioSemanalTest {

    private static final ZoneId BOGOTA = ZoneId.of("America/Bogota");
    private static final ZoneId NUEVA_YORK = ZoneId.of("America/New_York");

    private static Ventana ventana(DayOfWeek dia, String inicio, String fin) {
        return new Ventana(dia, LocalTime.parse(inicio), LocalTime.parse(fin));
    }

    @Test
    void withoutWindowsThereIsNoRestriction() {
        HorarioSemanal horario = HorarioSemanal.de(List.of(), BOGOTA);

        assertThat(horario.restringe()).isFalse();
        assertThat(horario.abiertoEn(Instant.parse("2026-11-11T04:00:00Z"))).isTrue();
        assertThat(horario.proximaApertura(Instant.parse("2026-11-11T04:00:00Z"))).isEmpty();
    }

    @Test
    void windowsAreReadInTheEstablishmentZoneNotInUtc() {
        // Monday 2026-11-09 20:00-23:00 in Bogota (UTC-5) is 2026-11-10 01:00-04:00 UTC.
        HorarioSemanal horario = HorarioSemanal.de(List.of(ventana(DayOfWeek.MONDAY, "20:00", "23:00")), BOGOTA);

        assertThat(horario.restringe()).isTrue();
        assertThat(horario.abiertoEn(Instant.parse("2026-11-10T02:00:00Z"))).isTrue();
        assertThat(horario.abiertoEn(Instant.parse("2026-11-10T01:00:00Z"))).isTrue();
        assertThat(horario.abiertoEn(Instant.parse("2026-11-10T04:00:00Z"))).isFalse();
        assertThat(horario.abiertoEn(Instant.parse("2026-11-09T21:00:00Z"))).isFalse(); // 16:00 in Bogota
        assertThat(horario.proximaApertura(Instant.parse("2026-11-09T21:00:00Z")))
                .contains(Instant.parse("2026-11-10T01:00:00Z"));
        assertThat(horario.proximaApertura(Instant.parse("2026-11-10T05:00:00Z")))
                .contains(Instant.parse("2026-11-17T01:00:00Z"));
    }

    @Test
    void aWindowEndingAtOrBeforeItsStartEndsTheNextDay() {
        // Friday 2026-11-13 22:00 to Saturday 02:00 in Bogota.
        Ventana viernes = ventana(DayOfWeek.FRIDAY, "22:00", "02:00");
        HorarioSemanal horario = HorarioSemanal.de(List.of(viernes), BOGOTA);

        assertThat(viernes.cruzaMedianoche()).isTrue();
        assertThat(ventana(DayOfWeek.FRIDAY, "20:00", "23:59").cruzaMedianoche()).isFalse();
        assertThat(horario.abiertoEn(Instant.parse("2026-11-14T02:59:00Z"))).isFalse(); // Fri 21:59
        assertThat(horario.abiertoEn(Instant.parse("2026-11-14T03:00:00Z"))).isTrue(); // Fri 22:00
        assertThat(horario.abiertoEn(Instant.parse("2026-11-14T06:30:00Z"))).isTrue(); // Sat 01:30
        assertThat(horario.abiertoEn(Instant.parse("2026-11-14T07:00:00Z"))).isFalse(); // Sat 02:00
    }

    @Test
    void aSundayWindowCrossingMidnightReachesMonday() {
        HorarioSemanal horario = HorarioSemanal.de(List.of(ventana(DayOfWeek.SUNDAY, "23:00", "01:00")), BOGOTA);

        assertThat(horario.abiertoEn(Instant.parse("2026-11-16T05:30:00Z"))).isTrue(); // Monday 00:30
        assertThat(horario.abiertoEn(Instant.parse("2026-11-16T06:00:00Z"))).isFalse(); // Monday 01:00
    }

    @Test
    void aWindowAcrossTheSpringDaylightSavingChangeLastsItsWallClockHours() {
        // New York moves from UTC-5 to UTC-4 on Sunday 2026-03-08 at 02:00. Saturday 22:00 (EST) to Sunday 03:00
        // (EDT) is 03:00Z to 07:00Z: four real hours, not the five a UTC computation would give.
        HorarioSemanal horario = HorarioSemanal.de(List.of(ventana(DayOfWeek.SATURDAY, "22:00", "03:00")), NUEVA_YORK);

        assertThat(horario.abiertoEn(Instant.parse("2026-03-08T03:00:00Z"))).isTrue();
        assertThat(horario.abiertoEn(Instant.parse("2026-03-08T06:59:00Z"))).isTrue();
        assertThat(horario.abiertoEn(Instant.parse("2026-03-08T07:00:00Z"))).isFalse();
        assertThat(horario.abiertoEn(Instant.parse("2026-03-08T07:30:00Z"))).isFalse();
    }

    @Test
    void theNextOpeningUsesTheOffsetInForceOnThatDay() {
        // Sunday 10:00 in New York: 15:00Z before the change (EST), 14:00Z on 2026-03-08 (EDT).
        HorarioSemanal horario = HorarioSemanal.de(List.of(ventana(DayOfWeek.SUNDAY, "10:00", "12:00")), NUEVA_YORK);

        assertThat(horario.proximaApertura(Instant.parse("2026-03-07T12:00:00Z")))
                .contains(Instant.parse("2026-03-08T14:00:00Z"));
        assertThat(horario.proximaApertura(Instant.parse("2026-03-01T00:00:00Z")))
                .contains(Instant.parse("2026-03-01T15:00:00Z"));
    }

    @Test
    void aWindowStartingInsideTheSkippedHourStartsWhenTheClockResumes() {
        HorarioSemanal horario = HorarioSemanal.de(List.of(ventana(DayOfWeek.SUNDAY, "02:30", "04:00")), NUEVA_YORK);

        assertThat(horario.abiertoEn(Instant.parse("2026-03-08T07:29:00Z"))).isFalse();
        assertThat(horario.abiertoEn(Instant.parse("2026-03-08T07:30:00Z"))).isTrue(); // 03:30 EDT
        assertThat(horario.abiertoEn(Instant.parse("2026-03-08T08:00:00Z"))).isFalse(); // 04:00 EDT
    }

    @Test
    void overlapsAreDetectedWithinADayAcrossMidnightAndAcrossTheWeekEnd() {
        assertThat(HorarioSemanal.seSolapan(ventana(DayOfWeek.MONDAY, "20:00", "23:00"),
                ventana(DayOfWeek.MONDAY, "22:00", "23:30"))).isTrue();
        assertThat(HorarioSemanal.seSolapan(ventana(DayOfWeek.MONDAY, "22:00", "02:00"),
                ventana(DayOfWeek.TUESDAY, "01:00", "03:00"))).isTrue();
        assertThat(HorarioSemanal.seSolapan(ventana(DayOfWeek.SUNDAY, "23:00", "02:00"),
                ventana(DayOfWeek.MONDAY, "01:00", "03:00"))).isTrue();
        assertThat(HorarioSemanal.seSolapan(ventana(DayOfWeek.MONDAY, "01:00", "03:00"),
                ventana(DayOfWeek.SUNDAY, "23:00", "02:00"))).isTrue();
        assertThat(HorarioSemanal.seSolapan(ventana(DayOfWeek.MONDAY, "20:00", "22:00"),
                ventana(DayOfWeek.MONDAY, "22:00", "23:00"))).isFalse();
        assertThat(HorarioSemanal.seSolapan(ventana(DayOfWeek.SUNDAY, "22:00", "00:00"),
                ventana(DayOfWeek.MONDAY, "00:00", "03:00"))).isFalse();
        assertThat(HorarioSemanal.seSolapan(ventana(DayOfWeek.MONDAY, "20:00", "23:00"),
                ventana(DayOfWeek.TUESDAY, "20:00", "23:00"))).isFalse();
    }
}
