package cue.edu.co.yousongbackend.cola;

import cue.edu.co.yousongbackend.integracion.AmbitoEvento;
import cue.edu.co.yousongbackend.integracion.EventoOutboxPublisher;
import cue.edu.co.yousongbackend.sesion.EstadoSesion;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The version of the queue of a session ({@code sesion.cola_version}): every change of the queue (a request, a
 * vote, a playback starting or ending, the close) increments it once, and the incremented value is the
 * {@code colaVersion} of the snapshots and the {@code queue_version} of the {@code queue.changed} event.
 *
 * <p>Concurrency: {@link #avanzar} is an atomic {@code UPDATE ... RETURNING}, so it also takes the session row
 * lock until the transaction ends. Every queue change calls it FIRST, which makes all queue changes of one
 * session run one after another (no lost vote, no lost version, no vote on a request a concurrent playback just
 * finished). A rejected change rolls back, and its increment with it. Queue changes are short (no provider call
 * inside), and a session close takes the same row lock, so it waits for them too.
 */
@Component
public class VersionCola {

    /** The session as the queue change found it, after its version was incremented. */
    public record SesionBloqueada(UUID id, UUID establecimientoId, EstadoSesion estado, long colaVersion) {
    }

    private static final String PRODUCTOR = "backend.cola";

    private final JdbcTemplate jdbc;
    private final EventoOutboxPublisher eventos;

    VersionCola(JdbcTemplate jdbc, EventoOutboxPublisher eventos) {
        this.jdbc = jdbc;
        this.eventos = eventos;
    }

    /** Increments the queue version and locks the session row; empty when the session does not exist. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<SesionBloqueada> avanzar(UUID sesionId) {
        List<SesionBloqueada> filas = jdbc.query("UPDATE operacion.sesion SET cola_version = cola_version + 1 "
                        + "WHERE id = ? RETURNING id, establecimiento_id, estado, cola_version",
                (fila, n) -> new SesionBloqueada(fila.getObject("id", UUID.class),
                        fila.getObject("establecimiento_id", UUID.class), EstadoSesion.valueOf(fila.getString("estado")),
                        fila.getLong("cola_version")),
                sesionId);
        return filas.stream().findFirst();
    }

    /** Records {@code queue.changed} for the version a change produced, with the queue size after it. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publicarCambio(UUID establecimientoId, UUID sesionId, long colaVersion, String motivo, UUID correlacionId) {
        Integer tamano = jdbc.queryForObject("SELECT count(*) FROM operacion.solicitud WHERE sesion_id = ? "
                + "AND estado IN ('PENDIENTE', 'RESERVADA')", Integer.class, sesionId);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("queue_version", colaVersion);
        payload.put("reason_code", motivo);
        payload.put("queue_size", tamano == null ? 0 : tamano);
        eventos.publicar("queue.changed", AmbitoEvento.B, PRODUCTOR, correlacionId, establecimientoId, sesionId,
                "SESION", sesionId, colaVersion, payload);
    }
}
