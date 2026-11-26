package cue.edu.co.yousongbackend.catalogo;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The music genre of a video, as a Spanish label, from YouTube's {@code topicDetails.topicCategories}: a list of
 * Wikipedia article URLs such as {@code https://en.wikipedia.org/wiki/Pop_music}. YouTube does not tag every
 * video, so the genre is often unknown ({@code null}, never an empty string).
 *
 * <p>The table below is the whole mapping, in PRECEDENCE order: when a video carries several topics, the one
 * listed first wins, whatever the order YouTube gives them in. The order goes from the most specific to the most
 * generic: Latin subgenres first, then styles, then the regional umbrellas ("Música latina"), then Pop (YouTube tags
 * almost every chart song as Pop), then the catch-alls. The generic {@code Music} and {@code Entertainment} topics,
 * and every topic not listed (films, games, sports...), are ignored.
 *
 * <pre>
 *  #  Wikipedia slug            Label
 *  1  Reggaeton                 Reggaetón
 *  2  Bachata_(music)           Bachata
 *  3  Merengue_music            Merengue
 *  4  Salsa_music               Salsa
 *  5  Vallenato                 Vallenato
 *  6  Cumbia                    Cumbia
 *  7  Reggae                    Reggae
 *  8  Hip_hop_music             Hip hop
 *  9  Electronic_dance_music    Electrónica
 * 10  Electronic_music          Electrónica
 * 11  Dance_music               Electrónica
 * 12  Heavy_metal_music         Metal
 * 13  Rock_music                Rock
 * 14  Rhythm_and_blues          R&amp;B
 * 15  Soul_music                Soul
 * 16  Jazz                      Jazz
 * 17  Blues                     Blues
 * 18  Classical_music           Clásica
 * 19  Country_music             Country
 * 20  Folk_music                Folk
 * 21  Christian_music           Cristiana
 * 22  Latin_music               Música latina
 * 23  Music_of_Latin_America    Música latina
 * 24  Pop_music                 Pop
 * 25  Independent_music         Independiente
 * 26  Music_of_Asia             Música asiática
 * </pre>
 *
 * The official music topics of the YouTube Data API (Christian, Classical, Country, Electronic, Hip hop,
 * Independent, Jazz, Music of Asia, Music of Latin America, Pop, Reggae, Rhythm and blues, Rock, Soul) are all
 * covered; the other rows are Wikipedia articles YouTube may also return. There is no Trap or Urban topic in the
 * official list.
 *
 * <p>Parsing is lenient about the URL (http or https, any {@code *.wikipedia.org} language host, percent-encoding,
 * query strings, fragments, trailing slashes, letter case) and strict about what it is: anything that is not a
 * {@code /wiki/} article of Wikipedia is ignored.
 */
public final class GeneroMusical {

    /** Source recorded in {@code cancion.fuente_clasificacion} for a genre taken from these topics. */
    public static final String FUENTE = "youtube_topic";

    /** Longest label; {@code cancion.genero_propio} is varchar(60). */
    static final int MAXIMO = 60;

    private static final Map<String, String> TABLA = new LinkedHashMap<>();
    private static final Map<String, Integer> PRECEDENCIA = new LinkedHashMap<>();

    static {
        fila("Reggaeton", "Reggaetón");
        fila("Bachata_(music)", "Bachata");
        fila("Merengue_music", "Merengue");
        fila("Salsa_music", "Salsa");
        fila("Vallenato", "Vallenato");
        fila("Cumbia", "Cumbia");
        fila("Reggae", "Reggae");
        fila("Hip_hop_music", "Hip hop");
        fila("Electronic_dance_music", "Electrónica");
        fila("Electronic_music", "Electrónica");
        fila("Dance_music", "Electrónica");
        fila("Heavy_metal_music", "Metal");
        fila("Rock_music", "Rock");
        fila("Rhythm_and_blues", "R&B");
        fila("Soul_music", "Soul");
        fila("Jazz", "Jazz");
        fila("Blues", "Blues");
        fila("Classical_music", "Clásica");
        fila("Country_music", "Country");
        fila("Folk_music", "Folk");
        fila("Christian_music", "Cristiana");
        fila("Latin_music", "Música latina");
        fila("Music_of_Latin_America", "Música latina");
        fila("Pop_music", "Pop");
        fila("Independent_music", "Independiente");
        fila("Music_of_Asia", "Música asiática");
    }

    private GeneroMusical() {
    }

    private static void fila(String slug, String etiqueta) {
        String clave = slug.toLowerCase(Locale.ROOT);
        PRECEDENCIA.put(clave, PRECEDENCIA.size());
        TABLA.put(clave, etiqueta);
    }

    /**
     * The label of the most specific known topic among {@code urls}, or null when none is known (no topics, only
     * generic or unknown ones, or nothing that is a Wikipedia article URL).
     */
    public static String de(Iterable<String> urls) {
        if (urls == null) {
            return null;
        }
        String mejor = null;
        int precedencia = Integer.MAX_VALUE;
        for (String url : urls) {
            String slug = slug(url);
            Integer lugar = slug == null ? null : PRECEDENCIA.get(slug);
            if (lugar != null && lugar < precedencia) {
                precedencia = lugar;
                mejor = TABLA.get(slug);
            }
        }
        return mejor;
    }

    /** The documented slugs in precedence order (for tests and documentation). */
    static List<String> slugs() {
        return new ArrayList<>(PRECEDENCIA.keySet());
    }

    /** The lower-cased article slug of a Wikipedia URL, or null when it is not one. */
    private static String slug(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            return null;
        }
        String esquema = uri.getScheme();
        String host = uri.getHost();
        String ruta = uri.getPath();
        if (esquema == null || host == null || ruta == null
                || !(esquema.equalsIgnoreCase("https") || esquema.equalsIgnoreCase("http"))) {
            return null;
        }
        String dominio = host.toLowerCase(Locale.ROOT);
        if (!dominio.equals("wikipedia.org") && !dominio.endsWith(".wikipedia.org")) {
            return null;
        }
        String articulo = ruta.replaceAll("/+$", "");
        if (!articulo.startsWith("/wiki/") || articulo.length() == "/wiki/".length()) {
            return null;
        }
        return articulo.substring("/wiki/".length()).replace(' ', '_').toLowerCase(Locale.ROOT);
    }
}
