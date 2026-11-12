package cue.edu.co.yousongbackend.catalogo;

import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.util.regex.Pattern;

/** The shape of a YouTube video id: 11 characters of the URL-safe alphabet. */
public final class VideoIdYoutube {

    private static final Pattern FORMATO = Pattern.compile("[A-Za-z0-9_-]{11}");

    /** At most this many characters of a rejected value are shown back, so a huge value is never echoed. */
    static final int MAXIMO_MOSTRADO = 20;

    private VideoIdYoutube() {
    }

    public static boolean valido(String videoId) {
        return videoId != null && FORMATO.matcher(videoId).matches();
    }

    /**
     * The 400 {@code INVALID_VIDEO_ID} for a rejected value. The detail names at most its first
     * {@value #MAXIMO_MOSTRADO} characters (then {@code …}), with control characters shown as {@code ?}.
     */
    public static ApiException invalido(String videoId) {
        return ApiException.badRequest("INVALID_VIDEO_ID", "'" + mostrable(videoId)
                + "' is not a YouTube video id (11 characters: letters, digits, '-' or '_')");
    }

    static String mostrable(String valor) {
        if (valor == null) {
            return "null";
        }
        StringBuilder mostrado = new StringBuilder();
        valor.codePoints().limit(MAXIMO_MOSTRADO)
                .forEach(c -> mostrado.appendCodePoint(invisible(c) ? '?' : c));
        if (valor.codePointCount(0, valor.length()) > MAXIMO_MOSTRADO) {
            mostrado.append('…');
        }
        return mostrado.toString();
    }

    /** Control, format (e.g. bidi overrides) and line/paragraph separator characters. */
    private static boolean invisible(int c) {
        int tipo = Character.getType(c);
        return Character.isISOControl(c) || tipo == Character.FORMAT || tipo == Character.LINE_SEPARATOR
                || tipo == Character.PARAGRAPH_SEPARATOR;
    }
}
