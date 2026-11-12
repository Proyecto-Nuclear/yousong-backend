package cue.edu.co.yousongbackend.reproduccion;

import cue.edu.co.yousongbackend.control.AuditoriaService;
import cue.edu.co.yousongbackend.control.ResultadoAuditoria;
import cue.edu.co.yousongbackend.catalogo.BloqueosVigentes;
import cue.edu.co.yousongbackend.cola.ColaConsultas;
import cue.edu.co.yousongbackend.cola.ColaConsultas.Fila;
import cue.edu.co.yousongbackend.cola.ColaConsultas.Sonando;
import cue.edu.co.yousongbackend.cola.EstadoSolicitud;
import cue.edu.co.yousongbackend.cola.VersionCola;
import cue.edu.co.yousongbackend.cola.VersionCola.SesionBloqueada;
import cue.edu.co.yousongbackend.integracion.AmbitoEvento;
import cue.edu.co.yousongbackend.integracion.EventoOutboxPublisher;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.EliminacionResponse;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.FinReproduccionResponse;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.InicioReproduccionResponse;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.MotivoSalto;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.ResultadoFin;
import cue.edu.co.yousongbackend.reproduccion.ReproduccionDtos.SaltoResponse;
import cue.edu.co.yousongbackend.reproduccion.RotacionListaDefecto.ItemElegido;
import cue.edu.co.yousongbackend.sesion.EstadoSesion;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * MINIMAL staff playback driver: staff (the owner) start the next song, say how it ended, skip it with a reason,
 * and remove waiting requests from the queue. It has NO station lease, heartbeat or fencing; the web playback
 * station (lease, fencing tokens, and the server reporting the end of every song) is a later deliverable.
 *
 * <p>What plays next ({@link #siguiente}, {@link #saltar}): the head of the queue (most votes, then oldest) when a
 * request of a song that is not blocked is waiting, origin SOLICITUD; otherwise the next item of the default list
 * the session pinned (see {@link RotacionListaDefecto}), origin DEFECTO, without creating a request; 409
 * {@code QUEUE_EMPTY} when neither exists. "La lista por defecto cubre una cola vacía y retorna a las solicitudes
 * al finalizar el video actual": a request that arrives while a default song plays never interrupts it, it plays
 * at the next {@link #siguiente}. This unit provides that transition decision; the automatic advance happens when
 * the station (or the owner) calls {@code siguiente} after reporting the end of a song. Every start publishes
 * {@code playback.started}, and {@code playback.source_changed} (E19) when its origin differs from the previous
 * playback of the session (the first playback of a session is a change from null).
 *
 * <p>{@code reproduccion.estacion_id} is NOT NULL, so a playback needs a station row: the enabled station of the
 * session when there is one, otherwise a station of the session created here once (disabled, no lease, named
 * {@value #NOMBRE_ESTACION}) and reused afterwards.
 *
 * <p>Concurrency: like every queue change, every operation starts with {@link VersionCola#avanzar} (session row
 * lock), so two starts run one after the other and the second finds the first playing; {@code uq_reproduccion_activa}
 * stays as the backstop. Votes and content blocks take the same lock, so a vote never lands on a request this
 * driver just finished, and a block is always seen by the next pick.
 */
@Service
public class ReproduccionService {

    static final String NOMBRE_ESTACION = "Staff playback (minimal driver)";

    private static final String PRODUCTOR = "backend.reproduccion";

    private final VersionCola versionCola;
    private final ColaConsultas consultas;
    private final RotacionListaDefecto rotacion;
    private final BloqueosVigentes bloqueos;
    private final EventoOutboxPublisher eventos;
    private final AuditoriaService auditoria;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    ReproduccionService(VersionCola versionCola, ColaConsultas consultas, RotacionListaDefecto rotacion,
                        BloqueosVigentes bloqueos, EventoOutboxPublisher eventos, AuditoriaService auditoria,
                        JdbcTemplate jdbc, Clock clock) {
        this.versionCola = versionCola;
        this.consultas = consultas;
        this.rotacion = rotacion;
        this.bloqueos = bloqueos;
        this.eventos = eventos;
        this.auditoria = auditoria;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Starts the next song: the head of the queue, else the default list (see class doc). */
    @Transactional
    public InicioReproduccionResponse siguiente(UUID sesionId) {
        SesionBloqueada sesion = bloquearEnOperacion(sesionId);
        if (!jdbc.queryForList("SELECT 1 FROM operacion.reproduccion WHERE sesion_id = ? AND terminada_at IS NULL",
                sesionId).isEmpty()) {
            throw enCurso();
        }
        UUID correlacion = UUID.randomUUID();
        InicioReproduccionResponse inicio = iniciar(sesion, ahora(), correlacion).orElseThrow(() ->
                ApiException.conflict("QUEUE_EMPTY", "There is no request waiting and nothing to play from the default list"));
        versionCola.publicarCambio(sesion.establecimientoId(), sesionId, sesion.colaVersion(), "playback.started",
                correlacion);
        return inicio;
    }

    /**
     * Ends the open playback with the outcome staff report, and finishes its request the same way (a default-list
     * playback has none). A SALTADA result records its {@link MotivoSalto} (SALTO_MANUAL when absent) and an audit
     * row; a reason with any other result is 400 {@code SKIP_REASON_NOT_ALLOWED}.
     */
    @Transactional
    public FinReproduccionResponse terminar(UUID sesionId, ResultadoFin resultado, String motivoCodigo) {
        MotivoSalto motivo = motivoPara(resultado, motivoCodigo);
        SesionBloqueada sesion = bloquearEnOperacion(sesionId);
        UUID correlacion = UUID.randomUUID();
        FinReproduccionResponse fin = finalizar(sesion, resultado, motivo, ahora(), correlacion);
        versionCola.publicarCambio(sesion.establecimientoId(), sesionId, sesion.colaVersion(), "playback.finished",
                correlacion);
        return fin;
    }

    /**
     * Skips the playing song with a reason and, in the same transaction (one queue version), starts what plays
     * next like {@link #siguiente}. The skip succeeds even when nothing is left to play: {@code siguiente} is null.
     */
    @Transactional
    public SaltoResponse saltar(UUID sesionId, String motivoCodigo) {
        MotivoSalto motivo = motivoPara(ResultadoFin.SALTADA, motivoCodigo);
        SesionBloqueada sesion = bloquearEnOperacion(sesionId);
        UUID correlacion = UUID.randomUUID();
        Instant ahora = ahora();
        FinReproduccionResponse fin = finalizar(sesion, ResultadoFin.SALTADA, motivo, ahora, correlacion);
        InicioReproduccionResponse siguiente = iniciar(sesion, ahora, correlacion).orElse(null);
        versionCola.publicarCambio(sesion.establecimientoId(), sesionId, sesion.colaVersion(),
                siguiente == null ? "playback.finished" : "playback.started", correlacion);
        return new SaltoResponse(fin, siguiente);
    }

    /**
     * Takes a waiting request (PENDIENTE or RESERVADA) of the session out of the queue: it is marked SALTADA with
     * reason {@value ReproduccionDtos#ELIMINADA_POR_PERSONAL}, never deleted, and its votes stay as evidence. 404
     * {@code REQUEST_NOT_FOUND} for a request of another session; 409 {@code REQUEST_NOT_ACTIVE} when it is
     * playing or already finished (skip the playing one instead).
     */
    @Transactional
    public EliminacionResponse eliminarDeCola(UUID sesionId, UUID solicitudId) {
        SesionBloqueada sesion = bloquearEnOperacion(sesionId);
        List<String> estados = jdbc.queryForList("SELECT estado FROM operacion.solicitud WHERE id = ? AND sesion_id = ?",
                String.class, solicitudId, sesionId);
        if (estados.isEmpty()) {
            throw ApiException.notFound("REQUEST_NOT_FOUND", "The request does not exist in this session");
        }
        EstadoSolicitud estado = EstadoSolicitud.valueOf(estados.get(0));
        if (estado != EstadoSolicitud.PENDIENTE && estado != EstadoSolicitud.RESERVADA) {
            throw ApiException.conflict("REQUEST_NOT_ACTIVE",
                    "The request is not waiting in the queue; skip it if it is playing");
        }
        Instant ahora = ahora();
        Map<String, Object> cambio = jdbc.queryForMap("UPDATE operacion.solicitud SET estado = 'SALTADA', "
                + "finalizada_at = ?, version = version + 1 WHERE id = ? RETURNING cancion_id, version",
                Timestamp.from(ahora), solicitudId);

        UUID correlacion = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request_id", solicitudId);
        payload.put("song_id", cambio.get("cancion_id"));
        payload.put("reason_code", ReproduccionDtos.ELIMINADA_POR_PERSONAL);
        eventos.publicar("request.removed", AmbitoEvento.B, PRODUCTOR, correlacion, sesion.establecimientoId(),
                sesionId, "SOLICITUD", solicitudId, ((Number) cambio.get("version")).longValue(), payload);
        versionCola.publicarCambio(sesion.establecimientoId(), sesionId, sesion.colaVersion(), "request_removed",
                correlacion);
        auditoria.registrar(sesion.establecimientoId(), "REQUEST_REMOVED", "SOLICITUD", solicitudId, ResultadoAuditoria.EXITO, correlacion);
        return new EliminacionResponse(solicitudId, EstadoSolicitud.SALTADA, ReproduccionDtos.ELIMINADA_POR_PERSONAL,
                sesion.colaVersion());
    }

    /** Starts the head of the queue or the next default-list item; empty when there is nothing to play. */
    private Optional<InicioReproduccionResponse> iniciar(SesionBloqueada sesion, Instant ahora, UUID correlacion) {
        UUID sesionId = sesion.id();
        Set<UUID> bloqueadas = bloqueos.bloqueadas(sesion.establecimientoId(), ahora);
        Optional<Fila> cabeza = consultas.enEspera(sesionId).stream()
                .filter(fila -> !bloqueadas.contains(fila.cancionId())).findFirst();
        Optional<ItemElegido> item = cabeza.isPresent() ? Optional.empty() : rotacion.siguiente(sesionId, bloqueadas);
        if (cabeza.isEmpty() && item.isEmpty()) {
            return Optional.empty();
        }
        OrigenReproduccion origen = cabeza.isPresent() ? OrigenReproduccion.SOLICITUD : OrigenReproduccion.DEFECTO;
        OrigenReproduccion anterior = jdbc.queryForList("SELECT origen FROM operacion.reproduccion WHERE sesion_id = ? "
                        + "ORDER BY preparada_at DESC, id DESC LIMIT 1", String.class, sesionId).stream()
                .findFirst().map(OrigenReproduccion::valueOf).orElse(null);
        UUID estacionId = estacionPara(sesion);
        UUID reproduccionId = UUID.randomUUID();
        UUID solicitudId = cabeza.map(Fila::solicitudId).orElse(null);
        UUID itemListaId = item.map(ItemElegido::itemListaId).orElse(null);
        UUID cancionId = cabeza.map(Fila::cancionId).orElseGet(() -> item.get().cancionId());
        long votos = cabeza.map(Fila::votos).orElse(0L);

        if (solicitudId != null) {
            jdbc.update("UPDATE operacion.solicitud SET estado = 'REPRODUCIENDO', version = version + 1 WHERE id = ?",
                    solicitudId);
        }
        try {
            jdbc.update("INSERT INTO operacion.reproduccion (id, sesion_id, cancion_id, estacion_id, origen, solicitud_id, "
                            + "item_lista_id, preparada_at, iniciada_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    reproduccionId, sesionId, cancionId, estacionId, origen.name(), solicitudId, itemListaId,
                    Timestamp.from(ahora), Timestamp.from(ahora));
        } catch (DuplicateKeyException e) {
            throw enCurso();
        }
        Sonando sonando = consultas.sonando(sesionId).orElseThrow();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("playback_id", reproduccionId);
        payload.put("origin", origen.name());
        payload.put("request_id", solicitudId);
        payload.put("list_item_id", itemListaId);
        payload.put("song_id", cancionId);
        payload.put("votes_at_start", votos);
        eventos.publicar("playback.started", AmbitoEvento.B, PRODUCTOR, correlacion, sesion.establecimientoId(),
                sesionId, "REPRODUCCION", reproduccionId, 0, payload);
        if (origen != anterior) {
            Map<String, Object> cambio = new LinkedHashMap<>();
            cambio.put("from_source", anterior == null ? null : anterior.name());
            cambio.put("to_source", origen.name());
            cambio.put("reproduction_id", reproduccionId);
            eventos.publicar("playback.source_changed", AmbitoEvento.B, PRODUCTOR, correlacion,
                    sesion.establecimientoId(), sesionId, "REPRODUCCION", reproduccionId, 0, cambio);
        }
        return Optional.of(new InicioReproduccionResponse(reproduccionId, origen, solicitudId, sonando.cancion(), ahora,
                votos, sesion.colaVersion()));
    }

    /** Ends the open playback (and its request, if any); 409 {@code NO_ACTIVE_PLAYBACK} when nothing plays. */
    private FinReproduccionResponse finalizar(SesionBloqueada sesion, ResultadoFin resultado, MotivoSalto motivo,
                                              Instant ahora, UUID correlacion) {
        List<Abierta> abiertas = jdbc.query("SELECT id, solicitud_id FROM operacion.reproduccion "
                        + "WHERE sesion_id = ? AND terminada_at IS NULL",
                (fila, n) -> new Abierta(fila.getObject("id", UUID.class), fila.getObject("solicitud_id", UUID.class)),
                sesion.id());
        Abierta abierta = abiertas.stream().findFirst().orElseThrow(() ->
                ApiException.conflict("NO_ACTIVE_PLAYBACK", "Nothing is playing in this session"));
        String motivoCodigo = motivo == null ? null : motivo.name();

        jdbc.update("UPDATE operacion.reproduccion SET terminada_at = ?, resultado = ?, motivo_codigo = ? WHERE id = ?",
                Timestamp.from(ahora), resultado.resultado().name(), motivoCodigo, abierta.id());
        if (abierta.solicitudId() != null) {
            jdbc.update("UPDATE operacion.solicitud SET estado = ?, finalizada_at = ?, version = version + 1 WHERE id = ?",
                    resultado.estadoSolicitud().name(), Timestamp.from(ahora), abierta.solicitudId());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("playback_id", abierta.id());
        payload.put("request_id", abierta.solicitudId());
        payload.put("resultado", resultado.resultado().name());
        if (motivoCodigo != null) {
            payload.put("reason_code", motivoCodigo);
        }
        eventos.publicar("playback.finished", AmbitoEvento.B, PRODUCTOR, correlacion, sesion.establecimientoId(),
                sesion.id(), "REPRODUCCION", abierta.id(), 1, payload);
        if (resultado == ResultadoFin.SALTADA) {
            auditoria.registrar(sesion.establecimientoId(), "PLAYBACK_SKIPPED", "REPRODUCCION", abierta.id(), ResultadoAuditoria.EXITO, correlacion);
        }
        return new FinReproduccionResponse(abierta.id(), abierta.solicitudId(), resultado.resultado(), motivoCodigo,
                ahora, sesion.colaVersion());
    }

    /** The skip reason of a SALTADA result (SALTO_MANUAL when absent); none for the other results. */
    private static MotivoSalto motivoPara(ResultadoFin resultado, String codigo) {
        if (resultado != ResultadoFin.SALTADA) {
            if (codigo != null) {
                throw ApiException.badRequest("SKIP_REASON_NOT_ALLOWED", "Only a SALTADA result takes a motivoCodigo");
            }
            return null;
        }
        if (codigo == null) {
            return MotivoSalto.SALTO_MANUAL;
        }
        for (MotivoSalto motivo : MotivoSalto.values()) {
            if (motivo.name().equals(codigo)) {
                return motivo;
            }
        }
        throw ApiException.badRequest("INVALID_SKIP_REASON", "motivoCodigo must be one of " + List.of(MotivoSalto.values()));
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
