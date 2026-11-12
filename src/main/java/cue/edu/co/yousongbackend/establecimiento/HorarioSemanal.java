package cue.edu.co.yousongbackend.establecimiento;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The weekly windows in which an establishment serves participants, evaluated in the establishment's time zone.
 *
 * <p>A window is a wall-clock range of one weekday; when {@code fin} is not after {@code inicio} it ends on the
 * next day. Each occurrence is turned into real instants with {@link ZonedDateTime#of}, so daylight-saving
 * changes are honoured: a start inside a skipped hour begins when the clock resumes, an ambiguous time takes the
 * earlier offset, and a window across a change lasts its wall-clock hours (never computed by adding a UTC
 * duration). Occurrences are half-open: open at {@code inicio}, closed at {@code fin}.
 *
 * <p>An empty set means "no restriction": the establishment is governed by its session state alone.
 */
public final class HorarioSemanal {

    private static final int MINUTOS_SEMANA = 7 * 24 * 60;

    /** One weekly window, as the owner configured it. */
    public record Ventana(DayOfWeek dia, LocalTime inicio, LocalTime fin) {

        public Ventana {
            Objects.requireNonNull(dia, "dia");
            Objects.requireNonNull(inicio, "inicio");
            Objects.requireNonNull(fin, "fin");
        }

        /** Derived, never chosen: a window ending at or before its start ends the next day. */
        public boolean cruzaMedianoche() {
            return !fin.isAfter(inicio);
        }

        /** Wall-clock length in minutes (24 hours at most, when {@code fin == inicio}). */
        int duracionMinutos() {
            int minutos = (int) Duration.between(inicio, fin).toMinutes();
            return minutos > 0 ? minutos : minutos + 24 * 60;
        }

        /** Start as minutes since Monday 00:00 of the week. */
        int inicioSemanal() {
            return (dia.getValue() - 1) * 24 * 60 + inicio.getHour() * 60 + inicio.getMinute();
        }
    }

    /** One real occurrence of a window. */
    record Intervalo(Instant inicio, Instant fin) {

        boolean contiene(Instant instante) {
            return !instante.isBefore(inicio) && instante.isBefore(fin);
        }
    }

    private final List<Ventana> ventanas;
    private final ZoneId zona;

    private HorarioSemanal(List<Ventana> ventanas, ZoneId zona) {
        this.ventanas = List.copyOf(ventanas);
        this.zona = Objects.requireNonNull(zona, "zona");
    }

    public static HorarioSemanal de(List<Ventana> ventanas, ZoneId zona) {
        return new HorarioSemanal(ventanas, zona);
    }

    /** True when at least one window exists, so the establishment only serves inside them. */
    public boolean restringe() {
        return !ventanas.isEmpty();
    }

    /** Whether participants may be served at that instant (always, without windows). */
    public boolean abiertoEn(Instant instante) {
        return !restringe() || intervaloEn(instante).isPresent();
    }

    /** The occurrence containing the instant, if any. */
    Optional<Intervalo> intervaloEn(Instant instante) {
        LocalDate hoy = instante.atZone(zona).toLocalDate();
        // Only an occurrence that started today or yesterday (crossing midnight) can contain the instant.
        for (LocalDate dia = hoy.minusDays(1); !dia.isAfter(hoy); dia = dia.plusDays(1)) {
            for (Ventana ventana : ventanas) {
                if (ventana.dia() == dia.getDayOfWeek()) {
                    Intervalo intervalo = ocurrencia(ventana, dia);
                    if (intervalo.contiene(instante)) {
                        return Optional.of(intervalo);
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** The first start of a window strictly after the instant (empty without windows). */
    public Optional<Instant> proximaApertura(Instant instante) {
        LocalDate hoy = instante.atZone(zona).toLocalDate();
        Instant proxima = null;
        for (LocalDate dia = hoy; !dia.isAfter(hoy.plusDays(7)); dia = dia.plusDays(1)) {
            for (Ventana ventana : ventanas) {
                if (ventana.dia() == dia.getDayOfWeek()) {
                    Instant inicio = ocurrencia(ventana, dia).inicio();
                    if (inicio.isAfter(instante) && (proxima == null || inicio.isBefore(proxima))) {
                        proxima = inicio;
                    }
                }
            }
        }
        return Optional.ofNullable(proxima);
    }

    private Intervalo ocurrencia(Ventana ventana, LocalDate dia) {
        LocalDate diaFin = ventana.cruzaMedianoche() ? dia.plusDays(1) : dia;
        return new Intervalo(ZonedDateTime.of(dia, ventana.inicio(), zona).toInstant(),
                ZonedDateTime.of(diaFin, ventana.fin(), zona).toInstant());
    }

    /**
     * Whether two windows share any minute of the week, including a window crossing midnight into the next
     * day's window and Sunday night crossing into Monday. Touching ends ({@code 22:00} then {@code 22:00}) do
     * not overlap. Compared in wall-clock minutes, which is what the owner configures.
     */
    public static boolean seSolapan(Ventana a, Ventana b) {
        int inicioA = a.inicioSemanal();
        int finA = inicioA + a.duracionMinutos();
        for (int desplazamiento : new int[] {-MINUTOS_SEMANA, 0, MINUTOS_SEMANA}) {
            int inicioB = b.inicioSemanal() + desplazamiento;
            int finB = inicioB + b.duracionMinutos();
            if (inicioA < finB && inicioB < finA) {
                return true;
            }
        }
        return false;
    }
}
