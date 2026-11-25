package cue.edu.co.yousongbackend.destacadas;

import cue.edu.co.yousongbackend.catalogo.ArtistaDesdeCanal;
import cue.edu.co.yousongbackend.catalogo.BloqueosVigentes;
import cue.edu.co.yousongbackend.catalogo.CancionBusquedaResponse;
import cue.edu.co.yousongbackend.destacadas.DestacadasResponse.Pedida;
import cue.edu.co.yousongbackend.destacadas.DestacadasResponse.Reciente;
import cue.edu.co.yousongbackend.destacadas.DestacadasResponse.Sonada;
import cue.edu.co.yousongbackend.destacadas.DestacadasResponse.Votada;
import cue.edu.co.yousongbackend.establecimiento.Establecimiento;
import cue.edu.co.yousongbackend.establecimiento.EstablecimientoService;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import cue.edu.co.yousongbackend.shared.web.LimitadorPorDireccion;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Las más votadas, las más pedidas y las más sonadas" of an establishment, from what it already records.
 *
 * <ul>
 *   <li>Period: the sessions of the establishment opened in the last {@code dias} days (1..365, 30 by default).</li>
 *   <li>{@code masPedidas}: requests ({@code solicitud} rows) of the period per song, in any state (waiting,
 *       playing or finished) except the ones that never were a real request for the room: {@code BLOQUEADA} (taken
 *       out by a content block) and requests removed from the queue by the staff ({@code SALTADA} without any
 *       playback: a playing request that was skipped did sound, so it counts).</li>
 *   <li>{@code masVotadas}: every vote of those same requests (the requester's initial vote included).</li>
 *   <li>{@code masSonadas}: playbacks of the period that actually started and did not fail
 *       ({@code iniciada_at} set, {@code resultado} null or not {@code FALLIDA}), from requests and from the default
 *       list alike; the song playing now counts.</li>
 *   <li>{@code ultimasSonadas}: the last 10 playbacks that started, newest first, whatever the period and result;
 *       the open one is {@code enCurso}.</li>
 * </ul>
 * The rankings hold at most 10 songs, ordered by their count, then by their most recent activity (latest request,
 * vote or start), then by song id, so equal data always gives the same order. Songs the establishment blocks right
 * now are left out of the three rankings (they are not promoted publicly) but stay in {@code ultimasSonadas}, which
 * is history. Nothing identifies anyone. An inactive establishment still answers: its history is not private among
 * its patrons, and the other public reads (status, queue) answer for it too.
 *
 * <p>Everything is read in one read-only REPEATABLE READ transaction, so the four lists are one snapshot. At most
 * {@code app.destacadas.per-minute-per-address} (600) reads per client address and minute, checked first.
 */
@Service
public class DestacadasService {

    static final int DIAS_POR_DEFECTO = 30;
    static final int DIAS_MAXIMOS = 365;
    static final int MAXIMO = 10;

    private static final Pattern DIAS = Pattern.compile("\\d{1,3}");

    /**
     * Requests of the period that count. Parameters: establishment, period start, blocked song ids.
     * Alias {@code p}: {@code id}, {@code cancion_id}, {@code creada_at}.
     */
    private static final String PEDIDAS = "WITH pedidas AS (SELECT so.id, so.cancion_id, so.creada_at "
            + "FROM operacion.solicitud so JOIN operacion.sesion se ON se.id = so.sesion_id "
            + "WHERE se.establecimiento_id = ? AND se.abierta_at >= ? AND so.estado <> 'BLOQUEADA' "
            + "AND NOT (so.estado = 'SALTADA' AND NOT EXISTS "
            + "(SELECT 1 FROM operacion.reproduccion r WHERE r.solicitud_id = so.id)) "
            + "AND NOT (so.cancion_id = ANY (?))) ";

    private static final String ORDEN = " ORDER BY cuenta DESC, ultima DESC, cancion_id ASC LIMIT " + MAXIMO;

    private final JdbcTemplate jdbc;
    private final EstablecimientoService establecimientos;
    private final BloqueosVigentes bloqueos;
    private final Clock reloj;
    private final LimitadorPorDireccion limitador;

    DestacadasService(JdbcTemplate jdbc, EstablecimientoService establecimientos, BloqueosVigentes bloqueos,
                      Clock reloj, @Value("${app.destacadas.per-minute-per-address:600}") int porMinuto) {
        this.jdbc = jdbc;
        this.establecimientos = establecimientos;
        this.bloqueos = bloqueos;
        this.reloj = reloj;
        this.limitador = new LimitadorPorDireccion(porMinuto, "app.destacadas.per-minute-per-address",
                "Too many requests from this device: at most " + porMinuto + " per minute. Try again later");
    }

    /**
     * @param qrSlug    the establishment
     * @param dias      the period in days as sent ({@code null} = 30)
     * @param direccion the client address, only as a key of the per-minute limit
     * @throws ApiException 429 {@code RATE_LIMITED}, 400 {@code INVALID_PERIOD} unless {@code dias} is an integer
     *                      from 1 to 365, 404 {@code ESTABLISHMENT_NOT_FOUND}
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public DestacadasResponse destacadas(String qrSlug, String dias, String direccion) {
        limitador.registrar(direccion);
        int periodo = periodo(dias);
        Establecimiento establecimiento = establecimientos.obtenerPorSlug(qrSlug);
        UUID id = establecimiento.getId();
        Instant ahora = reloj.instant();
        Timestamp desde = Timestamp.from(ahora.minus(Duration.ofDays(periodo)));
        UUID[] bloqueadas = bloqueos.bloqueadas(id, ahora).toArray(UUID[]::new);

        List<Conteo> votadas = conteos(PEDIDAS + "SELECT p.cancion_id, count(v.id) AS cuenta, max(v.creado_at) AS ultima "
                + "FROM pedidas p JOIN operacion.voto v ON v.solicitud_id = p.id GROUP BY p.cancion_id" + ORDEN,
                id, desde, bloqueadas);
        List<Conteo> pedidas = conteos(PEDIDAS + "SELECT p.cancion_id, count(*) AS cuenta, max(p.creada_at) AS ultima "
                + "FROM pedidas p GROUP BY p.cancion_id" + ORDEN, id, desde, bloqueadas);
        List<Conteo> sonadas = conteos("SELECT r.cancion_id, count(*) AS cuenta, max(r.iniciada_at) AS ultima "
                + "FROM operacion.reproduccion r JOIN operacion.sesion se ON se.id = r.sesion_id "
                + "WHERE se.establecimiento_id = ? AND se.abierta_at >= ? AND r.iniciada_at IS NOT NULL "
                + "AND (r.resultado IS NULL OR r.resultado <> 'FALLIDA') AND NOT (r.cancion_id = ANY (?)) "
                + "GROUP BY r.cancion_id" + ORDEN, id, desde, bloqueadas);
        List<Sono> recientes = jdbc.query("SELECT r.cancion_id, r.iniciada_at, r.terminada_at IS NULL AS en_curso "
                        + "FROM operacion.reproduccion r JOIN operacion.sesion se ON se.id = r.sesion_id "
                        + "WHERE se.establecimiento_id = ? AND r.iniciada_at IS NOT NULL "
                        + "ORDER BY r.iniciada_at DESC, r.id DESC LIMIT " + MAXIMO,
                (fila, n) -> new Sono(fila.getObject("cancion_id", UUID.class),
                        fila.getObject("iniciada_at", OffsetDateTime.class).toInstant(), fila.getBoolean("en_curso")),
                id);

        Set<UUID> ids = new LinkedHashSet<>();
        votadas.forEach(c -> ids.add(c.cancionId()));
        pedidas.forEach(c -> ids.add(c.cancionId()));
        sonadas.forEach(c -> ids.add(c.cancionId()));
        recientes.forEach(s -> ids.add(s.cancionId()));
        Map<UUID, CancionBusquedaResponse> canciones = canciones(ids);

        return new DestacadasResponse(periodo,
                votadas.stream().map(c -> new Votada(canciones.get(c.cancionId()), c.cuenta())).toList(),
                pedidas.stream().map(c -> new Pedida(canciones.get(c.cancionId()), c.cuenta())).toList(),
                sonadas.stream().map(c -> new Sonada(canciones.get(c.cancionId()), c.cuenta())).toList(),
                recientes.stream().map(s -> new Reciente(canciones.get(s.cancionId()), s.sonoAt(), s.enCurso())).toList());
    }

    private static int periodo(String dias) {
        if (dias == null) {
            return DIAS_POR_DEFECTO;
        }
        String texto = dias.strip();
        int valor = DIAS.matcher(texto).matches() ? Integer.parseInt(texto) : 0;
        if (valor < 1 || valor > DIAS_MAXIMOS) {
            throw ApiException.badRequest("INVALID_PERIOD", "dias must be a whole number of days from 1 to "
                    + DIAS_MAXIMOS);
        }
        return valor;
    }

    private record Conteo(UUID cancionId, long cuenta) {
    }

    private record Sono(UUID cancionId, Instant sonoAt, boolean enCurso) {
    }

    /** Runs a ranking query whose parameters are: establishment, period start, blocked song ids. */
    private List<Conteo> conteos(String sql, UUID establecimientoId, Timestamp desde, UUID[] bloqueadas) {
        return jdbc.query(sql, (PreparedStatement ps) -> {
            ps.setObject(1, establecimientoId);
            ps.setTimestamp(2, desde);
            Array arreglo = ps.getConnection().createArrayOf("uuid", bloqueadas);
            ps.setArray(3, arreglo);
        }, (fila, n) -> new Conteo(fila.getObject("cancion_id", UUID.class), fila.getLong("cuenta")));
    }

    private Map<UUID, CancionBusquedaResponse> canciones(Set<UUID> ids) {
        Map<UUID, CancionBusquedaResponse> porId = new HashMap<>();
        if (ids.isEmpty()) {
            return porId;
        }
        jdbc.query("SELECT c.id, c.youtube_video_id, c.titulo, c.artista_verificado, c.canal_titulo, "
                        + "c.duracion_proveedor_s, c.miniatura_url, c.genero_propio FROM operacion.cancion c "
                        + "WHERE c.id = ANY (?)",
                ps -> ps.setArray(1, ps.getConnection().createArrayOf("uuid", new ArrayList<>(ids).toArray())),
                fila -> {
                    porId.put(fila.getObject("id", UUID.class), cancion(fila));
                });
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

    /** Forgets the per-address windows. Only for tests. */
    void reiniciar() {
        limitador.reiniciar();
    }
}
