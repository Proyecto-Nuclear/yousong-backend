package cue.edu.co.yousongbackend.catalogo;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the audit row ({@code operacion.auditoria}) of an owner moderation action: blocking or unblocking a
 * song, skipping the playing song, removing a request from the queue. It runs inside the transaction of the
 * action, so the action and its record commit together. The actor is the owner of the establishment (one owner
 * per establishment), so {@code personal_id} stays NULL: owners are identity-provider accounts, not staff rows.
 *
 * <p>Deliberately minimal and private to moderation; a general audit service can replace it later.
 */
@Component
public class AuditoriaModeracion {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    AuditoriaModeracion(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Records a successful action on an entity of the establishment. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void exito(UUID establecimientoId, String accion, String entidadTipo, UUID entidadRef, UUID correlacionId) {
        jdbc.update("INSERT INTO operacion.auditoria (id, establecimiento_id, accion_codigo, entidad_tipo, entidad_ref, "
                        + "resultado, correlacion_id, registrada_at) VALUES (?, ?, ?, ?, ?, 'EXITO', ?, ?)",
                UUID.randomUUID(), establecimientoId, accion, entidadTipo, entidadRef, correlacionId,
                Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS)));
    }
}
