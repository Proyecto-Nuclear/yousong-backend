package cue.edu.co.yousongbackend.establecimiento;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Builds the address segment of an establishment's QR from its name. Accents are removed and everything
 * that is not a letter or digit becomes a single hyphen, so the result always satisfies the database
 * constraint {@code [a-z0-9-]+}.
 */
final class Slug {

    /** The column holds 80 characters; this leaves room for the "-xxxxxx" random suffix of generated slugs. */
    static final int MAX_BASE_LENGTH = 70;

    private static final String FALLBACK = "local";

    private Slug() {
    }

    static String de(String texto) {
        String sinTildes = Normalizer.normalize(texto == null ? "" : texto, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String slug = sinTildes.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > MAX_BASE_LENGTH) {
            slug = slug.substring(0, MAX_BASE_LENGTH).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? FALLBACK : slug;
    }
}
