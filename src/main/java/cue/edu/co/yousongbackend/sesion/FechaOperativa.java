package cue.edu.co.yousongbackend.sesion;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Operating date of a session: the calendar date, in the establishment's own time zone, on which the
 * session opens. A night session that crosses midnight keeps this date until it closes.
 */
public final class FechaOperativa {

    private FechaOperativa() {
    }

    public static LocalDate de(Instant apertura, String zonaHoraria) {
        return LocalDate.ofInstant(apertura, ZoneId.of(zonaHoraria));
    }
}
