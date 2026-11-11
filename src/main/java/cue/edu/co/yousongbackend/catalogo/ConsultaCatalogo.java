package cue.edu.co.yousongbackend.catalogo;

import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.util.Locale;
import java.util.regex.Pattern;

/** Rules for the text a user types into the catalog (search and suggestions share them). */
final class ConsultaCatalogo {

    static final int MINIMA = 2;
    static final int MAXIMA = 100;

    private static final Pattern ESPACIOS = Pattern.compile("\\s+");

    /** Accented letters folded to plain ones; the SQL of {@link SugerenciasLocales} uses the same pairs. */
    static final String CON_ACENTO = "áàäâéèëêíìïîóòöôúùüûñÁÀÄÂÉÈËÊÍÌÏÎÓÒÖÔÚÙÜÛÑ";
    static final String SIN_ACENTO = "aaaaeeeeiiiioooouuuunaaaaeeeeiiiioooouuuun";

    private ConsultaCatalogo() {
    }

    /**
     * Trims, collapses whitespace and checks the length. The error message never repeats the text.
     *
     * @throws ApiException 400 {@code INVALID_QUERY} unless the result has 2 to 100 characters
     */
    static String normalizar(String consulta) {
        String texto = consulta == null ? "" : ESPACIOS.matcher(consulta.strip()).replaceAll(" ");
        if (texto.length() < MINIMA || texto.length() > MAXIMA) {
            throw ApiException.badRequest("INVALID_QUERY",
                    "The query must have between " + MINIMA + " and " + MAXIMA + " characters");
        }
        return texto;
    }

    /** Lowercase without the accents of {@link #CON_ACENTO}: the comparison key for matching and de-duplicating. */
    static String plegar(String texto) {
        String minusculas = texto.toLowerCase(Locale.ROOT);
        StringBuilder plegado = new StringBuilder(minusculas.length());
        for (int i = 0; i < minusculas.length(); i++) {
            char c = minusculas.charAt(i);
            int posicion = CON_ACENTO.indexOf(c);
            plegado.append(posicion < 0 ? c : SIN_ACENTO.charAt(posicion));
        }
        return plegado.toString();
    }
}
