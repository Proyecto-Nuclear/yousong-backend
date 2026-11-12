package cue.edu.co.yousongbackend.catalogo;

import cue.edu.co.yousongbackend.control.AuditoriaService;
import cue.edu.co.yousongbackend.control.ResultadoAuditoria;
import cue.edu.co.yousongbackend.catalogo.BloqueoDtos.BloqueoResponse;
import cue.edu.co.yousongbackend.catalogo.BloqueoDtos.CancionBloqueada;
import cue.edu.co.yousongbackend.catalogo.BloqueoDtos.CrearBloqueoRequest;
import cue.edu.co.yousongbackend.catalogo.BloqueoDtos.MotivoBloqueo;
import cue.edu.co.yousongbackend.cola.VersionCola;
import cue.edu.co.yousongbackend.cola.VersionCola.SesionBloqueada;
import cue.edu.co.yousongbackend.integracion.AmbitoEvento;
import cue.edu.co.yousongbackend.integracion.EventoOutboxPublisher;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Temporary or permanent content blocks of an establishment (REP-08): "Validar permisos del mismo local y fechas
 * ordenadas. La vigencia se evalúa contra desde_at, hasta_at y levantado_at; no se borra el antecedente".
 *
 * <p>Authorization is ownership: callers pass the establishment of the current owner, and a block id of another
 * establishment is answered 404. A blocked song cannot be requested or voted ({@code SONG_BLOCKED}), its waiting
 * requests in the session in operation leave the queue as BLOQUEADA, and neither the queue head nor the default
 * list rotation picks it. The song playing is never interrupted: the answer says so and the owner may skip it.
 * Lifting sets {@code levantado_at}; rows are never deleted. A permanent block has a NULL {@code hasta_at}.
 *
 * <p>Concurrency: creation takes a transaction advisory lock per establishment (one active block per song), then
 * the row lock of the session in operation, the same lock every vote takes first. So a vote either commits
 * before the block (and its request is then taken out of the queue) or runs after it and sees the block: no vote
 * is ever accepted on a BLOQUEADA request. Votes never take the advisory lock, so the lock order cannot deadlock.
 */
@Service
public class BloqueoContenidoService {

    static final int MAXIMO_MINUTOS = 10_080;

    private static final String PRODUCTOR = "backend.moderacion";
    private static final String AGREGADO = "BLOQUEO_CONTENIDO";

    private static final String SELECT = "SELECT b.id, b.motivo_codigo, b.desde_at, b.hasta_at, b.levantado_at, "
            + "c.youtube_video_id, c.titulo, c.artista_verificado, c.canal_titulo, c.miniatura_url "
            + "FROM operacion.bloqueo_contenido b JOIN operacion.cancion c ON c.id = b.cancion_id ";

    private final CancionRepository canciones;
    private final RegistroCancionSolicitada registro;
    private final BloqueosVigentes vigentes;
    private final VersionCola versionCola;
    private final EventoOutboxPublisher eventos;
    private final AuditoriaService auditoria;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaccion;
    private final Clock clock;

    BloqueoContenidoService(CancionRepository canciones, RegistroCancionSolicitada registro, BloqueosVigentes vigentes,
                            VersionCola versionCola, EventoOutboxPublisher eventos, AuditoriaService auditoria,
                            JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.canciones = canciones;
        this.registro = registro;
        this.vigentes = vigentes;
        this.versionCola = versionCola;
        this.eventos = eventos;
        this.auditoria = auditoria;
        this.jdbc = jdbc;
        this.transaccion = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Blocks a song. Not transactional itself: a song the catalog never stored is first looked up at YouTube (one
     * videos.list unit charged to the owner's daily budget and share, no transaction open) and stored; 404
     * {@code SONG_NOT_FOUND} when YouTube does not know it, 429 {@code SEARCH_BUDGET_EXHAUSTED} / 503
     * {@code CATALOG_QUOTA_EXCEEDED} when the owner budget or share is used. Then one short transaction creates the
     * block and applies its effects.
     */
    public BloqueoResponse bloquear(UUID establecimientoId, CrearBloqueoRequest request) {
        if (request == null || !VideoIdYoutube.valido(request.videoId())) {
            throw VideoIdYoutube.invalido(request == null ? null : request.videoId());
        }
        Duration duracion = duracion(request);
        MotivoBloqueo motivo = motivo(request.motivoCodigo());
        if (canciones.findByYoutubeVideoIdIn(List.of(request.videoId())).isEmpty()) {
            registro.asegurar(request.videoId(), OrigenBusqueda.propietario(establecimientoId));
        }
        return transaccion.execute(estado -> crear(establecimientoId, request.videoId(), duracion, motivo));
    }

    private BloqueoResponse crear(UUID establecimientoId, String videoId, Duration duracion, MotivoBloqueo motivo) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", fila -> null,
                "bloqueo_contenido:" + establecimientoId);
        Optional<UUID> sesionId = jdbc.queryForList("SELECT id FROM operacion.sesion WHERE establecimiento_id = ? "
                        + "AND estado IN ('ABIERTA', 'PAUSADA', 'CERRANDO') FOR UPDATE", UUID.class, establecimientoId)
                .stream().findFirst();
        UUID cancionId = jdbc.queryForObject("SELECT id FROM operacion.cancion WHERE youtube_video_id = ?", UUID.class,
                videoId);
        Instant ahora = ahora();
        if (vigentes.bloqueada(establecimientoId, cancionId, ahora)) {
            throw ApiException.conflict("SONG_ALREADY_BLOCKED", "This song is already blocked; lift that block first");
        }

        UUID id = UUID.randomUUID();
        Instant hasta = duracion == null ? null : ahora.plus(duracion);
        jdbc.update("INSERT INTO operacion.bloqueo_contenido (id, establecimiento_id, cancion_id, desde_at, hasta_at, "
                        + "motivo_codigo) VALUES (?, ?, ?, ?, ?, ?)", id, establecimientoId, cancionId, Timestamp.from(ahora),
                hasta == null ? null : Timestamp.from(hasta), motivo.name());

        UUID correlacion = UUID.randomUUID();
        int retiradas = 0;
        boolean reproduciendo = false;
        if (sesionId.isPresent()) {
            retiradas = jdbc.update("UPDATE operacion.solicitud SET estado = 'BLOQUEADA', finalizada_at = ?, "
                            + "version = version + 1 WHERE sesion_id = ? AND cancion_id = ? AND estado IN ('PENDIENTE', 'RESERVADA')",
                    Timestamp.from(ahora), sesionId.get(), cancionId);
            if (retiradas > 0) {
                SesionBloqueada sesion = versionCola.avanzar(sesionId.get()).orElseThrow();
                versionCola.publicarCambio(establecimientoId, sesion.id(), sesion.colaVersion(), "content_blocked",
                        correlacion);
            } else {
                // No request changed, but the block can still drop a song from the default-list preview.
                versionCola.notificarCambio(establecimientoId);
            }
            reproduciendo = !jdbc.queryForList("SELECT 1 FROM operacion.reproduccion WHERE sesion_id = ? "
                    + "AND cancion_id = ? AND terminada_at IS NULL", sesionId.get(), cancionId).isEmpty();
        }

        publicar(establecimientoId, sesionId.orElse(null), id, cancionId, "BLOCKED", hasta, motivo.name(), 0, correlacion);
        auditoria.registrar(establecimientoId, "CONTENT_BLOCKED", AGREGADO, id, ResultadoAuditoria.EXITO, correlacion);
        return buscar(establecimientoId, id, ahora).orElseThrow().conEfectos(retiradas, reproduciendo);
    }

    /** The blocks of the establishment, newest first: only those in force, or all of them with {@code todos}. */
    @Transactional(readOnly = true)
    public List<BloqueoResponse> listar(UUID establecimientoId, boolean todos) {
        Instant ahora = ahora();
        Timestamp momento = Timestamp.from(ahora);
        if (todos) {
            return jdbc.query(SELECT + "WHERE b.establecimiento_id = ? ORDER BY b.desde_at DESC, b.id",
                    (fila, n) -> respuesta(fila, ahora), establecimientoId);
        }
        return jdbc.query(SELECT + "WHERE b.establecimiento_id = ? AND " + BloqueosVigentes.VIGENTE
                + " ORDER BY b.desde_at DESC, b.id", (fila, n) -> respuesta(fila, ahora), establecimientoId, momento, momento);
    }

    /**
     * Lifts a block early by setting {@code levantado_at}; the row stays as history. Idempotent: lifting a block
     * that is already lifted or expired changes nothing and answers 200 with its current state. 404
     * {@code BLOCK_NOT_FOUND} for an id of another establishment.
     */
    @Transactional
    public BloqueoResponse levantar(UUID establecimientoId, UUID bloqueoId) {
        List<UUID> cancion = jdbc.queryForList("SELECT cancion_id FROM operacion.bloqueo_contenido "
                + "WHERE id = ? AND establecimiento_id = ? FOR UPDATE", UUID.class, bloqueoId, establecimientoId);
        if (cancion.isEmpty()) {
            throw ApiException.notFound("BLOCK_NOT_FOUND", "This establishment has no such block");
        }
        Instant ahora = ahora();
        BloqueoResponse actual = buscar(establecimientoId, bloqueoId, ahora).orElseThrow();
        if (!actual.vigente()) {
            return actual;
        }
        jdbc.update("UPDATE operacion.bloqueo_contenido SET levantado_at = ? WHERE id = ?", Timestamp.from(ahora), bloqueoId);
        UUID sesionId = jdbc.queryForList("SELECT id FROM operacion.sesion WHERE establecimiento_id = ? "
                + "AND estado IN ('ABIERTA', 'PAUSADA', 'CERRANDO')", UUID.class, establecimientoId).stream().findFirst()
                .orElse(null);
        if (sesionId != null) {
            // A lifted song can come back into the default-list preview of the public queue.
            versionCola.notificarCambio(establecimientoId);
        }
        UUID correlacion = UUID.randomUUID();
        publicar(establecimientoId, sesionId, bloqueoId, cancion.get(0), "LIFTED", actual.hastaAt(),
                actual.motivoCodigo(), 1, correlacion);
        auditoria.registrar(establecimientoId, "CONTENT_UNBLOCKED", AGREGADO, bloqueoId, ResultadoAuditoria.EXITO, correlacion);
        return buscar(establecimientoId, bloqueoId, ahora).orElseThrow();
    }

    /** E20 {@code moderation.changed}: action, until_at, reason_code. */
    private void publicar(UUID establecimientoId, UUID sesionId, UUID bloqueoId, UUID cancionId, String accion,
                          Instant hasta, String motivo, long version, UUID correlacion) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("action", accion);
        payload.put("block_id", bloqueoId);
        payload.put("song_id", cancionId);
        payload.put("until_at", hasta);
        payload.put("reason_code", motivo);
        eventos.publicar("moderation.changed", AmbitoEvento.B, PRODUCTOR, correlacion, establecimientoId, sesionId,
                AGREGADO, bloqueoId, version, payload);
    }

    private Optional<BloqueoResponse> buscar(UUID establecimientoId, UUID id, Instant ahora) {
        return jdbc.query(SELECT + "WHERE b.id = ? AND b.establecimiento_id = ?", (fila, n) -> respuesta(fila, ahora),
                id, establecimientoId).stream().findFirst();
    }

    private static BloqueoResponse respuesta(ResultSet fila, Instant ahora) throws SQLException {
        Instant desde = instante(fila, "desde_at");
        Instant hasta = instante(fila, "hasta_at");
        Instant levantado = instante(fila, "levantado_at");
        boolean vigente = levantado == null && !desde.isAfter(ahora) && (hasta == null || hasta.isAfter(ahora));
        CancionBloqueada cancion = new CancionBloqueada(fila.getString("titulo"),
                ArtistaDesdeCanal.etiqueta(fila.getString("artista_verificado"), fila.getString("canal_titulo")),
                fila.getString("miniatura_url"));
        return new BloqueoResponse(fila.getObject("id", UUID.class), fila.getString("youtube_video_id"), cancion,
                fila.getString("motivo_codigo"), desde, hasta, hasta == null, levantado, vigente, null, null);
    }

    private static Instant instante(ResultSet fila, String columna) throws SQLException {
        OffsetDateTime valor = fila.getObject(columna, OffsetDateTime.class);
        return valor == null ? null : valor.toInstant();
    }

    /** Exactly one of a duration of 1..10080 minutes or {@code permanente: true}; null means permanent. */
    private static Duration duracion(CrearBloqueoRequest request) {
        boolean permanente = Boolean.TRUE.equals(request.permanente());
        Integer minutos = request.duracionMinutos();
        if (permanente == (minutos != null) || (minutos != null && (minutos < 1 || minutos > MAXIMO_MINUTOS))) {
            throw ApiException.badRequest("INVALID_BLOCK_DURATION", "Give either duracionMinutos between 1 and "
                    + MAXIMO_MINUTOS + " or permanente: true");
        }
        return permanente ? null : Duration.ofMinutes(minutos);
    }

    private static MotivoBloqueo motivo(String codigo) {
        if (codigo != null) {
            for (MotivoBloqueo motivo : MotivoBloqueo.values()) {
                if (motivo.name().equals(codigo)) {
                    return motivo;
                }
            }
        }
        throw ApiException.badRequest("INVALID_BLOCK_REASON", "motivoCodigo must be one of "
                + List.of(MotivoBloqueo.values()));
    }

    private Instant ahora() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
