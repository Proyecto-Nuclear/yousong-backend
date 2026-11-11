package cue.edu.co.yousongbackend.catalogo;

import java.util.regex.Pattern;

/**
 * YouTube has no artist field, so the uploading channel is the best display label we have: auto-generated
 * artist channels are called "Name - Topic" and label channels "NameVEVO". This is only a LABEL. An artist is
 * "verified" solely when the song's {@code artista_verificado} column says so; the channel never fills it.
 */
public final class ArtistaDesdeCanal {

    private static final Pattern TOPIC = Pattern.compile("\\s*-\\s*Topic\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern VEVO = Pattern.compile("\\s*VEVO\\s*$", Pattern.CASE_INSENSITIVE);

    private ArtistaDesdeCanal() {
    }

    /** The channel title without its "- Topic" / "VEVO" suffix, or null when nothing useful is left. */
    public static String de(String tituloCanal) {
        if (tituloCanal == null) {
            return null;
        }
        String limpio = TOPIC.matcher(tituloCanal.strip()).replaceFirst("");
        limpio = VEVO.matcher(limpio).replaceFirst("").strip();
        return limpio.isEmpty() ? null : limpio;
    }

    /** What to show as the artist: the verified artist when there is one, otherwise the channel-derived label. */
    public static String etiqueta(String artistaVerificado, String tituloCanal) {
        return verificado(artistaVerificado) ? artistaVerificado : de(tituloCanal);
    }

    /** True only when the song's verified-artist column has a value. */
    public static boolean verificado(String artistaVerificado) {
        return artistaVerificado != null && !artistaVerificado.isBlank();
    }
}
