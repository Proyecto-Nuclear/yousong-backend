package cue.edu.co.yousongbackend.sesion;

import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Whether participants already inside a session may change its queue (request or vote) right now. Reading the
 * queue and its stream is never restricted here.
 *
 * <ul>
 *   <li>Session PAUSADA (manual session pause): 409 SESSION_PAUSED.</li>
 *   <li>Session CERRANDO or CERRADA: 409 SESSION_NOT_ACCEPTING.</li>
 *   <li>Establishment in maintenance: 409 ESTABLISHMENT_IN_MAINTENANCE (no session can be in operation then,
 *       so this is a backstop).</li>
 *   <li>Establishment paused by the owner (system pause): 409 ESTABLISHMENT_PAUSED.</li>
 * </ul>
 *
 * <p>The establishment flags are read without a lock: an action racing with the owner's pause may still be
 * accepted when it read the flags just before the pause committed. That is acceptable for an operational pause.
 */
@Component
public class ReglasDeParticipacion {

    private final JdbcTemplate jdbc;

    ReglasDeParticipacion(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Fails unless the session takes requests and votes now; {@code estado} null means the session is gone. */
    public void exigirAccionesDeCola(UUID establecimientoId, EstadoSesion estado) {
        if (estado == EstadoSesion.PAUSADA) {
            throw ApiException.conflict("SESSION_PAUSED", "The session is paused; try again when it resumes");
        }
        if (estado != EstadoSesion.ABIERTA) {
            throw ApiException.conflict("SESSION_NOT_ACCEPTING", "The session is not taking requests or votes right now");
        }
        List<Map<String, Object>> banderas = jdbc.queryForList(
                "SELECT pausado, en_mantenimiento FROM operacion.establecimiento WHERE id = ?", establecimientoId);
        if (banderas.isEmpty()) {
            return;
        }
        if (Boolean.TRUE.equals(banderas.get(0).get("en_mantenimiento"))) {
            throw enMantenimiento();
        }
        if (Boolean.TRUE.equals(banderas.get(0).get("pausado"))) {
            throw pausado();
        }
    }

    static ApiException pausado() {
        return ApiException.conflict("ESTABLISHMENT_PAUSED", "The establishment is paused; try again when it resumes");
    }

    static ApiException enMantenimiento() {
        return ApiException.conflict("ESTABLISHMENT_IN_MAINTENANCE", "The establishment is in maintenance");
    }
}
