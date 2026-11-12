package cue.edu.co.yousongbackend.establecimiento;

import cue.edu.co.yousongbackend.cola.VersionCola;
import cue.edu.co.yousongbackend.control.AuditoriaService;
import cue.edu.co.yousongbackend.control.ResultadoAuditoria;
import cue.edu.co.yousongbackend.integracion.AmbitoEvento;
import cue.edu.co.yousongbackend.integracion.EventoOutboxPublisher;
import cue.edu.co.yousongbackend.sesion.SesionService;
import cue.edu.co.yousongbackend.shared.web.ApiException;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owner switches that stop the service of the caller's establishment without deactivating it.
 *
 * <p><b>System pause</b> ({@code pausado}): public status PAUSADO; joins (409 ESTABLISHMENT_PAUSED), new sessions
 * and requests or votes of participants already inside are refused; reading the queue keeps working. The
 * session in operation, if any, keeps its own state (the system pause is independent from the session pause and
 * from {@code activo}) and the scheduled close never closes a session because of it.
 *
 * <p><b>Maintenance</b> ({@code en_mantenimiento}), between operating days: only with no session in operation
 * (409 SESSION_IN_PROGRESS). Starting it "cleans the lists" without deleting evidence: requests left
 * PENDIENTE/RESERVADA/REPRODUCIENDO in the establishment's (closed) sessions are finalized as NO_ATENDIDA_CIERRE,
 * never deleted; the live queue is refreshed and {@code maintenance.executed} (E22) is published. While in
 * maintenance the public status is MANTENIMIENTO and joins and new sessions are refused.
 *
 * <p>Every change locks the establishment row (which also serializes it with opening a session), publishes
 * {@code configuration.changed} and writes an audit row, all in one transaction.
 */
@Service
public class EstadoOperativoService {

    private final EstablecimientoService establecimientos;
    private final SesionService sesiones;
    private final CambiosConfiguracion cambios;
    private final EventoOutboxPublisher eventos;
    private final AuditoriaService auditoria;
    private final VersionCola versionCola;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    EstadoOperativoService(EstablecimientoService establecimientos, SesionService sesiones,
                           CambiosConfiguracion cambios, EventoOutboxPublisher eventos, AuditoriaService auditoria,
                           VersionCola versionCola, EntityManager entityManager, JdbcTemplate jdbc, Clock clock) {
        this.establecimientos = establecimientos;
        this.sesiones = sesiones;
        this.cambios = cambios;
        this.eventos = eventos;
        this.auditoria = auditoria;
        this.versionCola = versionCola;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The result of starting maintenance: the establishment and how many leftover requests were finalized. */
    public record MantenimientoResponse(EstablecimientoResponse establecimiento, int solicitudesFinalizadas) {
    }

    @Transactional
    public EstablecimientoResponse pausar(UUID establecimientoId) {
        Establecimiento establecimiento = establecimientos.obtenerBloqueando(establecimientoId);
        if (establecimiento.isPausado()) {
            throw ApiException.conflict("ESTABLISHMENT_ALREADY_PAUSED", "The establishment is already paused");
        }
        establecimiento.setPausado(true);
        return registrar(establecimiento, "pausado", "ESTABLECIMIENTO_PAUSADO");
    }

    @Transactional
    public EstablecimientoResponse reanudar(UUID establecimientoId) {
        Establecimiento establecimiento = establecimientos.obtenerBloqueando(establecimientoId);
        if (!establecimiento.isPausado()) {
            throw ApiException.conflict("ESTABLISHMENT_NOT_PAUSED", "The establishment is not paused");
        }
        establecimiento.setPausado(false);
        return registrar(establecimiento, "pausado", "ESTABLECIMIENTO_REANUDADO");
    }

    @Transactional
    public MantenimientoResponse iniciarMantenimiento(UUID establecimientoId) {
        Establecimiento establecimiento = establecimientos.obtenerBloqueando(establecimientoId);
        if (establecimiento.isEnMantenimiento()) {
            throw ApiException.conflict("ALREADY_IN_MAINTENANCE", "The establishment is already in maintenance");
        }
        if (sesiones.hayEnOperacion(establecimientoId)) {
            throw ApiException.conflict("SESSION_IN_PROGRESS", "Close the session in operation before maintenance");
        }
        establecimiento.setEnMantenimiento(true);

        // Finalized, never deleted: the history of every night stays queryable.
        int finalizadas = jdbc.update("UPDATE operacion.solicitud s SET estado = 'NO_ATENDIDA_CIERRE', "
                + "finalizada_at = ?, version = s.version + 1 FROM operacion.sesion se "
                + "WHERE s.sesion_id = se.id AND se.establecimiento_id = ? AND se.estado = 'CERRADA' "
                + "AND s.estado IN ('PENDIENTE', 'RESERVADA', 'REPRODUCIENDO')",
                Timestamp.from(clock.instant().truncatedTo(ChronoUnit.MICROS)), establecimientoId);
        UUID ultimaSesion = jdbc.queryForList("SELECT id FROM operacion.sesion WHERE establecimiento_id = ? "
                        + "AND estado = 'CERRADA' ORDER BY cerrada_at DESC NULLS LAST, abierta_at DESC LIMIT 1",
                UUID.class, establecimientoId).stream().findFirst().orElse(null);

        EstablecimientoResponse respuesta = registrar(establecimiento, "en_mantenimiento", "MANTENIMIENTO_INICIADO");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("session_ref", ultimaSesion);
        payload.put("projection_reset_count", finalizadas);
        payload.put("result", "OK");
        long version = eventos.siguienteVersion("ESTABLECIMIENTO", establecimientoId, "maintenance.executed");
        eventos.publicar("maintenance.executed", AmbitoEvento.O, "backend.mantenimiento", UUID.randomUUID(),
                establecimientoId, ultimaSesion, "ESTABLECIMIENTO", establecimientoId, version, payload);
        return new MantenimientoResponse(respuesta, finalizadas);
    }

    @Transactional
    public EstablecimientoResponse finalizarMantenimiento(UUID establecimientoId) {
        Establecimiento establecimiento = establecimientos.obtenerBloqueando(establecimientoId);
        if (!establecimiento.isEnMantenimiento()) {
            throw ApiException.conflict("NOT_IN_MAINTENANCE", "The establishment is not in maintenance");
        }
        establecimiento.setEnMantenimiento(false);
        return registrar(establecimiento, "en_mantenimiento", "MANTENIMIENTO_FINALIZADO");
    }

    private EstablecimientoResponse registrar(Establecimiento establecimiento, String campo, String accion) {
        entityManager.flush();
        cambios.publicar(establecimiento.getId(), List.of(campo));
        auditoria.registrar(establecimiento.getId(), accion, "ESTABLECIMIENTO", establecimiento.getId(),
                ResultadoAuditoria.EXITO);
        // Live subscribers of the public queue get a fresh snapshot once this commits.
        versionCola.notificarCambio(establecimiento.getId());
        return EstablecimientoResponse.de(establecimiento);
    }
}
