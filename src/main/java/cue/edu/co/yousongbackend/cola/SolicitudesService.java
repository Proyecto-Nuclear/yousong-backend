package cue.edu.co.yousongbackend.cola;

import cue.edu.co.yousongbackend.catalogo.BloqueosVigentes;
import cue.edu.co.yousongbackend.catalogo.CancionRepository;
import cue.edu.co.yousongbackend.catalogo.RegistroCancionSolicitada;
import cue.edu.co.yousongbackend.catalogo.OrigenBusqueda;
import cue.edu.co.yousongbackend.catalogo.VideoIdYoutube;
import cue.edu.co.yousongbackend.cola.ColaDtos.ApoyoResponse;
import cue.edu.co.yousongbackend.cola.VersionCola.SesionBloqueada;
import cue.edu.co.yousongbackend.integracion.AmbitoEvento;
import cue.edu.co.yousongbackend.integracion.EventoOutboxPublisher;
import cue.edu.co.yousongbackend.participante.ParticipanteAutenticado;
import cue.edu.co.yousongbackend.sesion.EstadoSesion;
import cue.edu.co.yousongbackend.sesion.ReglasDeParticipacion;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Song requests and votes of participants.
 *
 * <p>"Crear una solicitud registra también su primer voto": a request is created together with the creator's
 * initial vote ({@code es_inicial}) in one transaction, and requesting a song that already has an active request
 * in the session (PENDIENTE, RESERVADA or REPRODUCIENDO, {@code uq_solicitud_activa}) is a vote on that request.
 * "Un apoyo por participante y video en cada sesión" ({@code uq_voto_participacion_cancion}) and "No se permite
 * cambiar voto en el PMV": a vote is never changed or withdrawn.
 *
 * <p>"Una canción puede solicitarse de nuevo después de 30 minutos desde su fin, por participantes que aún no la
 * hayan apoyado en esa sesión": within {@code app.cola.re-request-minutes} of the {@code finalizada_at} of the
 * song's latest request it cannot be requested; after that a new request (next {@code ciclo}) is created.
 *
 * <p>Concurrency: every change starts with {@link VersionCola#avanzar}, which locks the session row, so queue
 * changes of one session are serialized and each one sees the previous one committed. The unique constraints
 * stay as a backstop: a violation is answered as {@code ALREADY_VOTED}, never as a server error. A song the
 * establishment blocked (see {@code catalogo.BloqueoContenidoService}) is answered 409 {@code SONG_BLOCKED}, and a song stored as {@code NO_DISPONIBLE} 409 {@code SONG_UNAVAILABLE},
 * for a new request and for a vote on an active one alike. A song unknown
 * to the catalog is looked up at YouTube BEFORE that transaction, so no provider call holds the lock.
 */
@Service
public class SolicitudesService {

    private static final String PRODUCTOR = "backend.cola";

    private final ColaConsultas consultas;
    private final ReglasDeParticipacion reglas;
    private final VersionCola versionCola;
    private final LimitadorAccionesCola limitador;
    private final CancionRepository canciones;
    private final RegistroCancionSolicitada registro;
    private final EventoOutboxPublisher eventos;
    private final BloqueosVigentes bloqueos;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaccion;
    private final Clock clock;
    private final Duration ventanaRepeticion;
    private final int maximoActivas;

    SolicitudesService(ColaConsultas consultas, ReglasDeParticipacion reglas, VersionCola versionCola,
                       LimitadorAccionesCola limitador,
                       CancionRepository canciones, RegistroCancionSolicitada registro, EventoOutboxPublisher eventos,
                       BloqueosVigentes bloqueos, JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                       Clock clock,
                       @Value("${app.cola.re-request-minutes:30}") long minutosRepeticion,
                       @Value("${app.cola.max-active-per-session:200}") int maximoActivas) {
        if (minutosRepeticion < 0) {
            throw new IllegalStateException("app.cola.re-request-minutes must not be negative");
        }
        if (maximoActivas <= 0) {
            throw new IllegalStateException("app.cola.max-active-per-session must be positive");
        }
        this.consultas = consultas;
        this.reglas = reglas;
        this.versionCola = versionCola;
        this.limitador = limitador;
        this.canciones = canciones;
        this.registro = registro;
        this.eventos = eventos;
        this.bloqueos = bloqueos;
        this.jdbc = jdbc;
        this.transaccion = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.ventanaRepeticion = Duration.ofMinutes(minutosRepeticion);
        this.maximoActivas = maximoActivas;
    }

    /**
     * Requests the song for the participant's session, or votes for it when it already has an active request.
     * Not transactional itself: a song the catalog never stored is first looked up at YouTube (one videos.list
     * unit charged to the participant's establishment, no transaction open; concurrent requests of the same video
     * share one lookup, see {@link RegistroCancionSolicitada}) and stored, then one short
     * transaction applies the request.
     */
    public ApoyoResponse solicitar(ParticipanteAutenticado participante, String videoId) {
        limitador.registrar(participante.participacionId());
        if (!VideoIdYoutube.valido(videoId)) {
            throw VideoIdYoutube.invalido(videoId);
        }
        if (canciones.findByYoutubeVideoIdIn(List.of(videoId)).isEmpty()) {
            // A cheap early answer so a paused session does not spend quota; the transaction checks again.
            reglas.exigirAccionesDeCola(participante.establecimientoId(), estadoActual(participante.sesionId()));
            registro.asegurar(videoId, OrigenBusqueda.participante(participante.establecimientoId()));
        }
        return transaccion.execute(estado -> solicitarEnCola(participante, videoId));
    }

    /** Supports an active request of the participant's own session. */
    public ApoyoResponse votar(ParticipanteAutenticado participante, UUID solicitudId) {
        limitador.registrar(participante.participacionId());
        return transaccion.execute(estado -> {
            SesionBloqueada sesion = bloquearAbierta(participante.sesionId());
            List<SolicitudActual> encontradas = jdbc.query("SELECT id, cancion_id, estado FROM operacion.solicitud "
                            + "WHERE id = ? AND sesion_id = ?",
                    (fila, n) -> new SolicitudActual(fila.getObject("id", UUID.class),
                            fila.getObject("cancion_id", UUID.class), EstadoSolicitud.valueOf(fila.getString("estado"))),
                    solicitudId, sesion.id());
            SolicitudActual solicitud = encontradas.stream().findFirst().orElseThrow(() ->
                    ApiException.notFound("REQUEST_NOT_FOUND", "The request does not exist in this session"));
            exigirNoBloqueada(sesion, solicitud.cancionId());
            exigirDisponible(solicitud.cancionId());
            if (!solicitud.estado().activa()) {
                throw ApiException.conflict("REQUEST_NOT_ACTIVE", "The request is no longer in the queue");
            }
            return apoyar(sesion, participante, solicitud, false, ahora(), UUID.randomUUID());
        });
    }

    private ApoyoResponse solicitarEnCola(ParticipanteAutenticado participante, String videoId) {
        SesionBloqueada sesion = bloquearAbierta(participante.sesionId());
        Instant ahora = ahora();
        UUID correlacion = UUID.randomUUID();

        CancionGuardada cancion = jdbc.query("SELECT id, disponibilidad FROM operacion.cancion WHERE youtube_video_id = ?",
                        (fila, n) -> new CancionGuardada(fila.getObject("id", UUID.class), fila.getString("disponibilidad")),
                        videoId).stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("SONG_NOT_FOUND", "The song is not in the catalog"));
        if ("NO_DISPONIBLE".equals(cancion.disponibilidad())) {
            throw noDisponible();
        }
        exigirNoBloqueada(sesion, cancion.id());

        Optional<SolicitudActual> activa = jdbc.query("SELECT id, cancion_id, estado FROM operacion.solicitud "
                        + "WHERE sesion_id = ? AND cancion_id = ? AND estado IN ('PENDIENTE', 'RESERVADA', 'REPRODUCIENDO')",
                (fila, n) -> new SolicitudActual(fila.getObject("id", UUID.class), fila.getObject("cancion_id", UUID.class),
                        EstadoSolicitud.valueOf(fila.getString("estado"))),
                sesion.id(), cancion.id()).stream().findFirst();
        if (activa.isPresent()) {
            return apoyar(sesion, participante, activa.get(), false, ahora, correlacion);
        }

        exigirFueraDeVentana(sesion.id(), cancion.id(), ahora);
        if (yaApoyo(participante.participacionId(), sesion.id(), cancion.id())) {
            throw yaVotado();
        }
        Integer activas = jdbc.queryForObject("SELECT count(*) FROM operacion.solicitud WHERE sesion_id = ? "
                + "AND estado IN ('PENDIENTE', 'RESERVADA', 'REPRODUCIENDO')", Integer.class, sesion.id());
        if (activas != null && activas >= maximoActivas) {
            throw ApiException.conflict("QUEUE_FULL", "The queue is full; support a song already in it");
        }

        Integer ciclo = jdbc.queryForObject("SELECT coalesce(max(ciclo), 0) + 1 FROM operacion.solicitud "
                + "WHERE sesion_id = ? AND cancion_id = ?", Integer.class, sesion.id(), cancion.id());
        UUID solicitudId = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO operacion.solicitud (id, sesion_id, cancion_id, creador_id, estado, ciclo, creada_at) "
                            + "VALUES (?, ?, ?, ?, 'PENDIENTE', ?, ?)",
                    solicitudId, sesion.id(), cancion.id(), participante.participacionId(), ciclo, Timestamp.from(ahora));
        } catch (DuplicateKeyException e) {
            // Unreachable while the session lock serializes queue changes; kept as a backstop.
            throw ApiException.conflict("REQUEST_CONFLICT", "The song was requested at the same moment; try again");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("request_id", solicitudId);
        payload.put("song_id", cancion.id());
        payload.put("created_at", ahora);
        payload.put("cycle", ciclo);
        eventos.publicar("request.created", AmbitoEvento.B, PRODUCTOR, correlacion, sesion.establecimientoId(),
                sesion.id(), "SOLICITUD", solicitudId, 0, payload);

        SolicitudActual creada = new SolicitudActual(solicitudId, cancion.id(), EstadoSolicitud.PENDIENTE);
        return apoyar(sesion, participante, creada, true, ahora, correlacion);
    }

    /** Records the participant's vote on the request, with its event and the queue change. */
    private ApoyoResponse apoyar(SesionBloqueada sesion, ParticipanteAutenticado participante, SolicitudActual solicitud,
                                 boolean inicial, Instant ahora, UUID correlacion) {
        if (!inicial && yaApoyo(participante.participacionId(), sesion.id(), solicitud.cancionId())) {
            throw yaVotado();
        }
        UUID votoId = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO operacion.voto (id, solicitud_id, participacion_id, sesion_id, cancion_id, es_inicial, "
                            + "creado_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    votoId, solicitud.id(), participante.participacionId(), sesion.id(), solicitud.cancionId(), inicial,
                    Timestamp.from(ahora));
        } catch (DuplicateKeyException e) {
            throw yaVotado();
        }
        long votos = consultas.votos(solicitud.id());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vote_id", votoId);
        payload.put("request_id", solicitud.id());
        payload.put("song_id", solicitud.cancionId());
        payload.put("is_initial", inicial);
        payload.put("votes_after", votos);
        eventos.publicar("vote.accepted", AmbitoEvento.B, PRODUCTOR, correlacion, sesion.establecimientoId(),
                sesion.id(), "VOTO", votoId, 0, payload);
        versionCola.publicarCambio(sesion.establecimientoId(), sesion.id(), sesion.colaVersion(),
                inicial ? "request.created" : "vote.accepted", correlacion);

        return new ApoyoResponse(solicitud.id(), votos, true, posicion(sesion.id(), solicitud), inicial);
    }

    /** 1-based place in the queue; null while the request is playing (it is no longer waiting). */
    private Integer posicion(UUID sesionId, SolicitudActual solicitud) {
        if (solicitud.estado() == EstadoSolicitud.REPRODUCIENDO) {
            return null;
        }
        List<ColaConsultas.Fila> cola = consultas.enEspera(sesionId);
        for (int i = 0; i < cola.size(); i++) {
            if (cola.get(i).solicitudId().equals(solicitud.id())) {
                return i + 1;
            }
        }
        return null;
    }

    private void exigirFueraDeVentana(UUID sesionId, UUID cancionId, Instant ahora) {
        List<OffsetDateTime> ultima = jdbc.queryForList("SELECT max(finalizada_at) FROM operacion.solicitud "
                + "WHERE sesion_id = ? AND cancion_id = ?", OffsetDateTime.class, sesionId, cancionId);
        OffsetDateTime fin = ultima.isEmpty() ? null : ultima.get(0);
        if (fin != null && fin.toInstant().plus(ventanaRepeticion).isAfter(ahora)) {
            throw ApiException.conflict("SONG_RECENTLY_PLAYED", "This song played less than "
                    + ventanaRepeticion.toMinutes() + " minutes ago; it can be requested again later");
        }
    }

    /**
     * A song the establishment blocked can be neither requested nor voted, also when a request of it is still
     * playing. Checked after the session lock, which a block creation also takes, so it sees every committed block.
     */
    private void exigirNoBloqueada(SesionBloqueada sesion, UUID cancionId) {
        if (bloqueos.bloqueada(sesion.establecimientoId(), cancionId, ahora())) {
            throw ApiException.conflict("SONG_BLOCKED", "This song is blocked in this establishment");
        }
    }

    /** A song YouTube reported unplayable here can be neither requested nor voted, also on an active request. */
    private void exigirDisponible(UUID cancionId) {
        if (!jdbc.queryForList("SELECT 1 FROM operacion.cancion WHERE id = ? AND disponibilidad = 'NO_DISPONIBLE'",
                cancionId).isEmpty()) {
            throw noDisponible();
        }
    }

    private boolean yaApoyo(UUID participacionId, UUID sesionId, UUID cancionId) {
        return !jdbc.queryForList("SELECT 1 FROM operacion.voto WHERE participacion_id = ? AND sesion_id = ? "
                + "AND cancion_id = ?", participacionId, sesionId, cancionId).isEmpty();
    }

    private SesionBloqueada bloquearAbierta(UUID sesionId) {
        SesionBloqueada sesion = versionCola.avanzar(sesionId).orElseThrow(() -> noAcepta());
        reglas.exigirAccionesDeCola(sesion.establecimientoId(), sesion.estado());
        return sesion;
    }

    private EstadoSesion estadoActual(UUID sesionId) {
        return jdbc.queryForList("SELECT estado FROM operacion.sesion WHERE id = ?", String.class, sesionId)
                .stream().findFirst().map(EstadoSesion::valueOf).orElse(null);
    }

    private static ApiException noAcepta() {
        return ApiException.conflict("SESSION_NOT_ACCEPTING", "The session is not taking requests or votes right now");
    }

    private static ApiException noDisponible() {
        return ApiException.conflict("SONG_UNAVAILABLE", "This song cannot be played here");
    }

    private static ApiException yaVotado() {
        return ApiException.conflict("ALREADY_VOTED", "You already supported this song in this session");
    }

    private Instant ahora() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private record CancionGuardada(UUID id, String disponibilidad) {
    }

    private record SolicitudActual(UUID id, UUID cancionId, EstadoSolicitud estado) {
    }
}
