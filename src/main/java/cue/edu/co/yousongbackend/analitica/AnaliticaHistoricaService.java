package cue.edu.co.yousongbackend.analitica;

import cue.edu.co.yousongbackend.analitica.AnaliticaResponse.GenerosJornada;
import cue.edu.co.yousongbackend.analitica.AnaliticaResponse.Jornada;
import cue.edu.co.yousongbackend.analitica.AnaliticaResponse.Participacion;
import cue.edu.co.yousongbackend.analitica.AnaliticaResponse.Popular;
import cue.edu.co.yousongbackend.analitica.AnaliticaResponse.Publicacion;
import cue.edu.co.yousongbackend.catalogo.ArtistaDesdeCanal;
import cue.edu.co.yousongbackend.catalogo.CancionBusquedaResponse;
import cue.edu.co.yousongbackend.establecimiento.PropietarioActual;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The published history of the caller's establishment, read only from {@code analitica.*} (what the pipeline
 * published; nothing is computed from the operational tables here).
 *
 * <ul>
 *   <li>Sessions: the last {@code jornadas} (1..30, 8 by default) sessions of the establishment that have a
 *       {@code publicacion_sesion} row, by operating date; returned oldest first. Unpublished sessions are ignored.</li>
 *   <li>{@code publicacion}: version and cut-off of the newest publication among them.</li>
 *   <li>{@code participacion}: rows of {@code dim_participacion} per session; active ones have at least one
 *       {@code hecho_solicitud} or {@code hecho_voto}.</li>
 *   <li>{@code canales}: {@code dim_participacion} of those sessions per channel.</li>
 *   <li>{@code ranking}: {@code hecho_voto} per song (the initial vote is one of them), top 8, with the
 *       {@code hecho_reproduccion} of the same song and sessions whose result is COMPLETADA (both origins). Ties go
 *       to more completions, then to the catalog id. Titles come from the current catalog through
 *       {@code dim_cancion.cancion_ref} ({@code operacion.cancion.id}), since the analytic model keeps none.</li>
 *   <li>{@code generosPorHora}: {@code hecho_solicitud} per {@code dim_tiempo.hora} (already local) and
 *       {@code dim_cancion.genero_propio} (null as "Sin género"), see {@link CeldaGenero}.</li>
 * </ul>
 * One read-only REPEATABLE READ transaction, so the whole answer is one snapshot of one publication state.
 */
@Service
public class AnaliticaHistoricaService {

    static final int JORNADAS_POR_DEFECTO = 8;
    static final int JORNADAS_MAXIMAS = 30;
    static final int RANKING = 8;

    private static final Pattern JORNADAS = Pattern.compile("\\d{1,3}");

    private final JdbcTemplate jdbc;
    private final PropietarioActual propietario;

    AnaliticaHistoricaService(JdbcTemplate jdbc, PropietarioActual propietario) {
        this.jdbc = jdbc;
        this.propietario = propietario;
    }

    /**
     * @param jornadas the number of sessions as sent ({@code null} = 8)
     * @throws ApiException 400 {@code INVALID_PERIOD} unless it is an integer from 1 to 30, 404
     *                      {@code NO_ESTABLISHMENT}
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnaliticaResponse historial(String jornadas) {
        int cuantas = jornadas(jornadas);
        UUID establecimiento = propietario.establecimientoId();

        List<Publicada> publicadas = new ArrayList<>(jdbc.query("SELECT s.id, s.fecha_operativa, p.version, "
                        + "p.publicada_at, p.corte_eventos_at FROM analitica.dim_sesion s "
                        + "JOIN analitica.publicacion_sesion p ON p.sesion_id = s.id WHERE s.establecimiento_id = ? "
                        + "ORDER BY s.fecha_operativa DESC, s.abierta_at DESC, s.id DESC LIMIT ?",
                (fila, n) -> new Publicada(fila.getObject("id", UUID.class),
                        fila.getObject("fecha_operativa", LocalDate.class),
                        new Publicacion(fila.getLong("version"), instante(fila, "publicada_at"),
                                instante(fila, "corte_eventos_at"))),
                establecimiento, cuantas));
        Collections.reverse(publicadas);
        Object[] ids = publicadas.stream().map(Publicada::sesionId).toArray();

        Publicacion publicacion = null;
        for (Publicada p : publicadas) {
            if (publicacion == null || p.publicacion().publicadaAt().isAfter(publicacion.publicadaAt())
                    || (p.publicacion().publicadaAt().equals(publicacion.publicadaAt())
                    && p.publicacion().version() > publicacion.version())) {
                publicacion = p.publicacion();
            }
        }

        Map<UUID, long[]> participantes = new HashMap<>();
        porSesiones("SELECT dp.sesion_id, count(*) AS ingresaron, count(*) FILTER (WHERE "
                        + "EXISTS (SELECT 1 FROM analitica.hecho_solicitud hs WHERE hs.participacion_id = dp.id) "
                        + "OR EXISTS (SELECT 1 FROM analitica.hecho_voto hv WHERE hv.participacion_id = dp.id)) AS activas "
                        + "FROM analitica.dim_participacion dp WHERE dp.sesion_id = ANY (?) GROUP BY dp.sesion_id", ids,
                fila -> participantes.put(fila.getObject("sesion_id", UUID.class),
                        new long[] {fila.getLong("ingresaron"), fila.getLong("activas")}));

        Map<String, Long> canales = new LinkedHashMap<>();
        canales.put("WEB", 0L);
        canales.put("ANDROID", 0L);
        porSesiones("SELECT canal, count(*) AS total FROM analitica.dim_participacion WHERE sesion_id = ANY (?) "
                + "GROUP BY canal", ids, fila -> {
                    String canal = fila.getString("canal");
                    if (canales.containsKey(canal)) {
                        canales.put(canal, fila.getLong("total"));
                    }
                });

        List<Object[]> votadas = new ArrayList<>();
        jdbc.query("WITH votos AS (SELECT cancion_id, count(*) AS votos FROM analitica.hecho_voto "
                        + "WHERE sesion_id = ANY (?) GROUP BY cancion_id), "
                        + "completas AS (SELECT cancion_id, count(*) AS completadas FROM analitica.hecho_reproduccion "
                        + "WHERE sesion_id = ANY (?) AND resultado = 'COMPLETADA' GROUP BY cancion_id) "
                        + "SELECT dc.cancion_ref, v.votos, coalesce(c.completadas, 0) AS completadas FROM votos v "
                        + "JOIN analitica.dim_cancion dc ON dc.id = v.cancion_id "
                        + "LEFT JOIN completas c ON c.cancion_id = v.cancion_id "
                        + "ORDER BY v.votos DESC, completadas DESC, dc.cancion_ref ASC LIMIT " + RANKING,
                ps -> {
                    ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids));
                    ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids));
                },
                (RowCallbackHandler) fila -> votadas.add(new Object[] {fila.getObject("cancion_ref", UUID.class),
                        fila.getLong("votos"), fila.getLong("completadas")}));
        Map<UUID, CancionBusquedaResponse> canciones = canciones(votadas.stream().map(v -> v[0]).toArray());
        List<Popular> ranking = votadas.stream().map(v -> new Popular(
                canciones.getOrDefault((UUID) v[0], new CancionBusquedaResponse(null, null, null, false, null, null,
                        null, null)), (long) v[1], (long) v[2])).toList();

        Map<UUID, List<CeldaGenero.Conteo>> conteos = new HashMap<>();
        jdbc.query("SELECT hs.sesion_id, t.hora, coalesce(dc.genero_propio, ?) AS genero, min(t.id) AS orden, "
                        + "count(*) AS solicitudes, count(DISTINCT hs.participacion_id) AS participantes "
                        + "FROM analitica.hecho_solicitud hs JOIN analitica.dim_tiempo t ON t.id = hs.tiempo_id "
                        + "JOIN analitica.dim_cancion dc ON dc.id = hs.cancion_id WHERE hs.sesion_id = ANY (?) "
                        + "GROUP BY 1, 2, 3",
                ps -> {
                    ps.setString(1, CeldaGenero.SIN_GENERO);
                    ps.setArray(2, ps.getConnection().createArrayOf("uuid", ids));
                },
                (RowCallbackHandler) fila -> conteos.computeIfAbsent(fila.getObject("sesion_id", UUID.class),
                        k -> new ArrayList<>()).add(new CeldaGenero.Conteo(fila.getLong("orden"), fila.getInt("hora"),
                        fila.getString("genero"), fila.getLong("solicitudes"), fila.getLong("participantes"))));

        List<Jornada> lista = new ArrayList<>();
        List<Participacion> participacion = new ArrayList<>();
        List<GenerosJornada> generos = new ArrayList<>();
        for (Publicada p : publicadas) {
            lista.add(new Jornada(p.sesionId(), p.fechaOperativa()));
            long[] cuenta = participantes.getOrDefault(p.sesionId(), new long[] {0, 0});
            participacion.add(new Participacion(p.sesionId(), cuenta[0], cuenta[1]));
            generos.add(new GenerosJornada(p.sesionId(), p.fechaOperativa(), p.fechaOperativa().getDayOfWeek().getValue(),
                    CeldaGenero.celdas(conteos.getOrDefault(p.sesionId(), List.of()))));
        }
        return new AnaliticaResponse(publicacion, lista, participacion, canales, ranking, generos);
    }

    private static int jornadas(String jornadas) {
        if (jornadas == null) {
            return JORNADAS_POR_DEFECTO;
        }
        String texto = jornadas.strip();
        int valor = JORNADAS.matcher(texto).matches() ? Integer.parseInt(texto) : 0;
        if (valor < 1 || valor > JORNADAS_MAXIMAS) {
            throw ApiException.badRequest("INVALID_PERIOD", "jornadas must be a whole number of sessions from 1 to "
                    + JORNADAS_MAXIMAS);
        }
        return valor;
    }

    private record Publicada(UUID sesionId, LocalDate fechaOperativa, Publicacion publicacion) {
    }

    private static Instant instante(ResultSet fila, String columna) throws SQLException {
        return fila.getObject(columna, OffsetDateTime.class).toInstant();
    }

    /** Runs a query whose only parameter is the array of session ids. */
    private void porSesiones(String sql, Object[] ids, RowCallbackHandler filas) {
        jdbc.query(sql, ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids)), filas);
    }

    /** Display data of catalog songs by {@code operacion.cancion.id}. */
    private Map<UUID, CancionBusquedaResponse> canciones(Object[] ids) {
        Map<UUID, CancionBusquedaResponse> porId = new HashMap<>();
        if (ids.length == 0) {
            return porId;
        }
        jdbc.query("SELECT c.id, c.youtube_video_id, c.titulo, c.artista_verificado, c.canal_titulo, "
                        + "c.duracion_proveedor_s, c.miniatura_url, c.genero_propio FROM operacion.cancion c "
                        + "WHERE c.id = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", ids)),
                (RowCallbackHandler) fila -> porId.put(fila.getObject("id", UUID.class), cancion(fila)));
        return porId;
    }

    private static CancionBusquedaResponse cancion(ResultSet fila) throws SQLException {
        String verificado = fila.getString("artista_verificado");
        String canal = fila.getString("canal_titulo");
        int duracion = fila.getInt("duracion_proveedor_s");
        Integer segundos = fila.wasNull() ? null : duracion;
        return new CancionBusquedaResponse(fila.getString("youtube_video_id"), fila.getString("titulo"),
                ArtistaDesdeCanal.etiqueta(verificado, canal), ArtistaDesdeCanal.verificado(verificado), canal,
                segundos, fila.getString("miniatura_url"), fila.getString("genero_propio"));
    }
}
