package cue.edu.co.yousongbackend.cola;

import cue.edu.co.yousongbackend.cola.ColaDtos.CancionCola;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads of the queue. The order is derived on every read and never stored ("no counter column"):
 * {@code COUNT(votos) DESC, creada_at ASC, id ASC} over the requests waiting (PENDIENTE), with a RESERVADA
 * request (one already handed to the player) kept at the head. The request playing is not part of the queue,
 * so popularity never interrupts the current song.
 */
@Component
public class ColaConsultas {

    /** A request waiting in the queue, with its support and its song. */
    public record Fila(UUID solicitudId, UUID cancionId, long votos, CancionCola cancion, Integer duracionOperativa) {
    }

    /**
     * The playback attempt open in the session. {@code origen} is {@code SOLICITUD} or {@code DEFECTO}
     * ({@code reproduccion.origen}); {@code solicitudId} is null for a default-list song.
     */
    public record Sonando(UUID reproduccionId, String origen, UUID solicitudId, UUID cancionId, CancionCola cancion,
                          Instant iniciadaAt, Integer duracionOperativa) {
    }

    private static final String CANCION = "c.youtube_video_id, c.titulo, c.artista_verificado, c.canal_titulo, "
            + "c.duracion_proveedor_s, c.miniatura_url, c.duracion_operativa_s";

    private final JdbcTemplate jdbc;

    ColaConsultas(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The queue in order (the first one plays next). */
    public List<Fila> enEspera(UUID sesionId) {
        return jdbc.query("SELECT s.id, s.cancion_id, " + CANCION + ", "
                        + "(SELECT count(*) FROM operacion.voto v WHERE v.solicitud_id = s.id) AS votos "
                        + "FROM operacion.solicitud s JOIN operacion.cancion c ON c.id = s.cancion_id "
                        + "WHERE s.sesion_id = ? AND s.estado IN ('PENDIENTE', 'RESERVADA') "
                        + "ORDER BY (s.estado = 'RESERVADA') DESC, votos DESC, s.creada_at ASC, s.id ASC",
                (fila, n) -> new Fila(fila.getObject("id", UUID.class), fila.getObject("cancion_id", UUID.class),
                        fila.getLong("votos"), cancion(fila), entero(fila, "duracion_operativa_s")),
                sesionId);
    }

    public Optional<Sonando> sonando(UUID sesionId) {
        return jdbc.query("SELECT r.id, r.origen, r.solicitud_id, r.cancion_id, r.iniciada_at, " + CANCION + " "
                        + "FROM operacion.reproduccion r JOIN operacion.cancion c ON c.id = r.cancion_id "
                        + "WHERE r.sesion_id = ? AND r.terminada_at IS NULL",
                (fila, n) -> {
                    OffsetDateTime iniciada = fila.getObject("iniciada_at", OffsetDateTime.class);
                    return new Sonando(fila.getObject("id", UUID.class), fila.getString("origen"),
                            fila.getObject("solicitud_id", UUID.class),
                            fila.getObject("cancion_id", UUID.class), cancion(fila),
                            iniciada == null ? null : iniciada.toInstant(), entero(fila, "duracion_operativa_s"));
                },
                sesionId).stream().findFirst();
    }

    /**
     * The songs the participant already supported in the session. Support is per song and session
     * ({@code uq_voto_participacion_cancion}), so it also covers a later cycle of the same song.
     */
    Set<UUID> cancionesApoyadas(UUID participacionId, UUID sesionId) {
        return new HashSet<>(jdbc.queryForList("SELECT cancion_id FROM operacion.voto "
                + "WHERE participacion_id = ? AND sesion_id = ?", UUID.class, participacionId, sesionId));
    }

    public long votos(UUID solicitudId) {
        Long votos = jdbc.queryForObject("SELECT count(*) FROM operacion.voto WHERE solicitud_id = ?", Long.class,
                solicitudId);
        return votos == null ? 0 : votos;
    }

    private static CancionCola cancion(ResultSet fila) throws SQLException {
        return CancionCola.de(fila.getString("youtube_video_id"), fila.getString("titulo"),
                fila.getString("artista_verificado"), fila.getString("canal_titulo"),
                entero(fila, "duracion_proveedor_s"), fila.getString("miniatura_url"));
    }

    private static Integer entero(ResultSet fila, String columna) throws SQLException {
        int valor = fila.getInt(columna);
        return fila.wasNull() ? null : valor;
    }
}
