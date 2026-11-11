package cue.edu.co.yousongbackend.catalogo;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Songs the system already knows ({@code operacion.cancion}) that match what the user is typing. Read only.
 *
 * <p>A song matches when it has a title, is not {@code NO_DISPONIBLE}, and EVERY word of the query is a
 * substring of "title + verified artist + channel title", ignoring case and accents. No PostgreSQL extension
 * is needed: accents are folded with {@code translate} using the same pairs as {@link ConsultaCatalogo#plegar}.
 * The user's words travel only as bind parameters, with the LIKE wildcards ({@code %}, {@code _}, {@code \})
 * escaped so they match literally.
 *
 * <p>Order: titles that start with the whole query, then songs where every word starts a word, then the
 * rest; within each group by folded title.
 */
@Component
class SugerenciasLocales {

    private static final String PLEGAR = "translate(lower(%s), '" + ConsultaCatalogo.CON_ACENTO + "', '"
            + ConsultaCatalogo.SIN_ACENTO + "')";
    private static final String BASE = "select youtube_video_id, titulo, artista_verificado, canal_titulo, "
            + "duracion_proveedor_s, miniatura_url from (select c.youtube_video_id, c.titulo, c.artista_verificado, "
            + "c.canal_titulo, c.duracion_proveedor_s, c.miniatura_url, "
            + PLEGAR.formatted("c.titulo || ' ' || coalesce(c.artista_verificado, '') || ' ' || coalesce(c.canal_titulo, '')")
            + " as pajar, " + PLEGAR.formatted("c.titulo") + " as titulo_plegado "
            + "from operacion.cancion c where c.titulo is not null and c.disponibilidad <> 'NO_DISPONIBLE') s where ";

    private final JdbcTemplate jdbc;

    SugerenciasLocales(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Up to {@code maximo} known songs for a normalized query, best first. */
    List<CancionBusquedaResponse> buscar(String consulta, int maximo) {
        String plegada = ConsultaCatalogo.plegar(consulta);
        Set<String> palabras = new LinkedHashSet<>(List.of(plegada.split(" ")));
        palabras.remove("");
        if (palabras.isEmpty()) {
            return List.of();
        }

        StringBuilder sql = new StringBuilder(BASE);
        List<Object> parametros = new ArrayList<>();
        List<String> condiciones = new ArrayList<>();
        StringBuilder inicioDePalabra = new StringBuilder();
        List<Object> parametrosOrden = new ArrayList<>();
        for (String palabra : palabras) {
            String literal = escapar(palabra);
            condiciones.add("s.pajar like ? escape '\\'");
            parametros.add("%" + literal + "%");
            inicioDePalabra.append(inicioDePalabra.isEmpty() ? "" : " and ")
                    .append("(s.pajar like ? escape '\\' or s.pajar like ? escape '\\')");
            parametrosOrden.add(literal + "%");
            parametrosOrden.add("% " + literal + "%");
        }
        sql.append(String.join(" and ", condiciones))
                .append(" order by case when s.titulo_plegado like ? escape '\\' then 0 when ")
                .append(inicioDePalabra)
                .append(" then 1 else 2 end, s.titulo_plegado, s.youtube_video_id limit ?");
        parametros.add(escapar(plegada) + "%");
        parametros.addAll(parametrosOrden);
        parametros.add(maximo);

        return jdbc.query(sql.toString(), (fila, numero) -> {
            String verificado = fila.getString("artista_verificado");
            String canal = fila.getString("canal_titulo");
            int duracion = fila.getInt("duracion_proveedor_s");
            return new CancionBusquedaResponse(fila.getString("youtube_video_id"), fila.getString("titulo"),
                    ArtistaDesdeCanal.etiqueta(verificado, canal), ArtistaDesdeCanal.verificado(verificado), canal,
                    fila.wasNull() ? null : duracion, fila.getString("miniatura_url"));
        }, parametros.toArray());
    }

    /** Escapes the LIKE wildcards so the user's text matches literally (escape character: backslash). */
    static String escapar(String texto) {
        return texto.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
