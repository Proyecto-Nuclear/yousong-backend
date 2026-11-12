package cue.edu.co.yousongbackend.reproduccion;

import cue.edu.co.yousongbackend.cola.ColaConsultas;
import cue.edu.co.yousongbackend.cola.ColaConsultas.Fila;
import cue.edu.co.yousongbackend.cola.VersionCola;
import cue.edu.co.yousongbackend.cola.VersionCola.SesionBloqueada;
import cue.edu.co.yousongbackend.integracion.AmbitoEvento;
import cue.edu.co.yousongbackend.integracion.EventoOutboxPublisher;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.FinReproduccionResponse;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.InicioReproduccionResponse;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.ResultadoFin;
import cue.edu.co.yousongbackend.sesion.EstadoSesion;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MINIMAL staff playback driver, only so "now playing" exists and the queue can advance: staff take the head of
 * the queue and later say how it ended. It has NO station lease, heartbeat or fencing; the web playback station
 * (lease, fencing tokens, automatic advance, default-list fallback) is a later deliverable and replaces this.
 *
 * <p>{@code reproduccion.estacion_id} is NOT NULL, so a playback needs a station row: the enabled station of the
 * session when there is one, otherwise a station of the session created here once (disabled, no lease, named
 * {@value #NOMBRE_ESTACION}) and reused afterwards.
 *
 * <p>Concurrency: like every queue change, both operations start with {@link VersionCola#avanzar} (session row
 * lock), so two starts run one after the other and the second finds the first playing; {@code uq_reproduccion_activa}
 * stays as the backstop. Votes take the same lock, so a vote never lands on a request this driver just finished.
 */
@Service
public class ReproduccionService {

    static final String NOMBRE_ESTACION = "Staff playback (minimal driver)";

    private static final String PRODUCTOR = "backend.reproduccion";

    private final VersionCola versionCola;
    private final ColaConsultas consultas;
    private final EventoOutboxPublisher eventos;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    ReproduccionService(VersionCola versionCola, ColaConsultas consultas, EventoOutboxPublisher eventos,
                        JdbcTemplate jdbc, Clock clock) {
        this.versionCola = versionCola;
        this.consultas = consultas;
        this.eventos = eventos;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Starts playing the head of the queue (most votes, then oldest). */
    @Transactional
    public InicioReproduccionResponse siguiente(UUID sesionId) {
        SesionBloqueada sesion = bloquearEnOperacion(sesionId);
        if (!jdbc.queryForList("SELECT 1 FROM operacion.reproduccion WHERE sesion_id = ? AND terminada_at IS NULL",
                sesionId).isEmpty()) {
            throw enCurso();
        }
        Fila cabeza = consultas.enEspera(sesionId).stream().findFirst()
                .orElseThrow(() -> ApiException.conflict("QUEUE_EMPTY", "There is no request waiting in the queue"));
        Instant ahora = ahora();
        UUID estacionId = estacionPara(sesion);

        jdbc.update("UPDATE operacion.solicitud SET estado = 'REPRODUCIENDO', version = version + 1 WHERE id = ?",
                cabeza.solicitudId());
        UUID reproduccionId = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO operacion.reproduccion (id, sesion_id, cancion_id, estacion_id, origen, solicitud_id, "
                            + "preparada_at, iniciada_at) VALUES (?, ?, ?, ?, 'SOLICITUD', ?, ?, ?)",
                    reproduccionId, sesionId, cabeza.cancionId(), estacionId, cabeza.solicitudId(),
                    Timestamp.from(ahora), Timestamp.from(ahora));
        } catch (DuplicateKeyException e) {
            throw enCurso();
        }

        UUID correlacion = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("playback_id", reproduccionId);
        payload.put("request_id", cabeza.solicitudId());
        payload.put("song_id", cabeza.cancionId());
        payload.put("votes_at_start", cabeza.votos());
        eventos.publicar("playback.started", AmbitoEvento.B, PRODUCTOR, correlacion, sesion.establecimientoId(),
                sesionId, "REPRODUCCION", reproduccionId, 0, payload);
        versionCola.publicarCambio(sesion.establecimientoId(), sesionId, sesion.colaVersion(), "playback.started",
                correlacion);

        return new InicioReproduccionResponse(reproduccionId, cabeza.solicitudId(), cabeza.cancion(), ahora,
                cabeza.votos(), sesion.colaVersion());
    }

    /** Ends the open playback with the outcome staff report, and finishes its request the same way. */
    @Transactional
    public FinReproduccionResponse terminar(UUID sesionId, ResultadoFin resultado) {
        SesionBloqueada sesion = bloquearEnOperacion(sesionId);
        List<Abierta> abiertas = jdbc.query("SELECT id, solicitud_id FROM operacion.reproduccion "
                        + "WHERE sesion_id = ? AND terminada_at IS NULL",
                (fila, n) -> new Abierta(fila.getObject("id", UUID.class), fila.getObject("solicitud_id", UUID.class)),
                sesionId);
        Abierta abierta = abiertas.stream().findFirst().orElseThrow(() ->
                ApiException.conflict("NO_ACTIVE_PLAYBACK", "Nothing is playing in this session"));
        Instant ahora = ahora();

        jdbc.update("UPDATE operacion.reproduccion SET terminada_at = ?, resultado = ? WHERE id = ?",
                Timestamp.from(ahora), resultado.resultado().name(), abierta.id());
        if (abierta.solicitudId() != null) {
            jdbc.update("UPDATE operacion.solicitud SET estado = ?, finalizada_at = ?, version = version + 1 WHERE id = ?",
                    resultado.estadoSolicitud().name(), Timestamp.from(ahora), abierta.solicitudId());
        }

        UUID correlacion = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("playback_id", abierta.id());
        payload.put("request_id", abierta.solicitudId());
        payload.put("resultado", resultado.resultado().name());
        eventos.publicar("playback.finished", AmbitoEvento.B, PRODUCTOR, correlacion, sesion.establecimientoId(),
                sesionId, "REPRODUCCION", abierta.id(), 1, payload);
        versionCola.publicarCambio(sesion.establecimientoId(), sesionId, sesion.colaVersion(), "playback.finished",
                correlacion);

        return new FinReproduccionResponse(abierta.id(), abierta.solicitudId(), resultado.resultado(), ahora,
                sesion.colaVersion());
    }

    private SesionBloqueada bloquearEnOperacion(UUID sesionId) {
        SesionBloqueada sesion = versionCola.avanzar(sesionId).orElseThrow(() ->
                ApiException.notFound("SESSION_NOT_FOUND", "Session " + sesionId + " does not exist"));
        if (sesion.estado() == EstadoSesion.CERRADA) {
            throw ApiException.conflict("SESSION_NOT_IN_OPERATION", "The session is closed");
        }
        return sesion;
    }

    /** The enabled station of the session, else the one this driver created for it, else a new one (see class doc). */
    private UUID estacionPara(SesionBloqueada sesion) {
        List<UUID> existentes = jdbc.queryForList("SELECT id FROM operacion.estacion WHERE sesion_id = ? "
                + "ORDER BY habilitada DESC, id LIMIT 1", UUID.class, sesion.id());
        if (!existentes.isEmpty()) {
            return existentes.get(0);
        }
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO operacion.estacion (id, establecimiento_id, sesion_id, nombre, habilitada) "
                + "VALUES (?, ?, ?, ?, false)", id, sesion.establecimientoId(), sesion.id(), NOMBRE_ESTACION);
        return id;
    }

    private static ApiException enCurso() {
        return ApiException.conflict("PLAYBACK_IN_PROGRESS", "A song is already playing; finish it first");
    }

    private Instant ahora() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private record Abierta(UUID id, UUID solicitudId) {
    }
}
