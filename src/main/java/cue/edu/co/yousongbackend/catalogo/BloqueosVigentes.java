package cue.edu.co.yousongbackend.catalogo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Which songs an establishment has blocked right now. A block is in force from {@code desde_at} until
 * {@code hasta_at} (exclusive; NULL = permanent) unless it was lifted ({@code levantado_at}); expired and lifted
 * blocks are kept as history and simply stop applying. Blocks are per establishment: one never affects another.
 */
@Component
public class BloqueosVigentes {

    /** SQL predicate of a block in force; parameters: the instant, twice. Alias {@code b}. */
    static final String VIGENTE = "b.levantado_at IS NULL AND b.desde_at <= ? AND (b.hasta_at IS NULL OR b.hasta_at > ?)";

    private final JdbcTemplate jdbc;

    BloqueosVigentes(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean bloqueada(UUID establecimientoId, UUID cancionId, Instant ahora) {
        Timestamp momento = Timestamp.from(ahora);
        return !jdbc.queryForList("SELECT 1 FROM operacion.bloqueo_contenido b WHERE b.establecimiento_id = ? "
                + "AND b.cancion_id = ? AND " + VIGENTE + " LIMIT 1", establecimientoId, cancionId, momento, momento)
                .isEmpty();
    }

    /** The ids of every song the establishment has blocked at that instant. */
    public Set<UUID> bloqueadas(UUID establecimientoId, Instant ahora) {
        Timestamp momento = Timestamp.from(ahora);
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT b.cancion_id FROM operacion.bloqueo_contenido b "
                + "WHERE b.establecimiento_id = ? AND " + VIGENTE, UUID.class, establecimientoId, momento, momento));
    }
}
