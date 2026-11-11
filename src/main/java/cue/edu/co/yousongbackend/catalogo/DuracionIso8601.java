package cue.edu.co.yousongbackend.catalogo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Converts the ISO-8601 durations YouTube uses ({@code PT3M47S}, {@code PT1H2M3S}, {@code P1DT2H}) to seconds. */
final class DuracionIso8601 {

    private static final Pattern FORMA = Pattern.compile("P(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?");

    private DuracionIso8601() {
    }

    /** Seconds, or null when the text is not a duration (or has no component at all, like "P" or "PT"). */
    static Integer segundos(String valor) {
        if (valor == null) {
            return null;
        }
        Matcher m = FORMA.matcher(valor.strip());
        if (!m.matches() || (m.group(1) == null && m.group(2) == null && m.group(3) == null && m.group(4) == null)) {
            return null;
        }
        try {
            long total = parte(m.group(1)) * 86_400 + parte(m.group(2)) * 3_600 + parte(m.group(3)) * 60 + parte(m.group(4));
            return total > Integer.MAX_VALUE ? null : (int) total;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long parte(String digitos) {
        return digitos == null ? 0 : Long.parseLong(digitos);
    }
}
