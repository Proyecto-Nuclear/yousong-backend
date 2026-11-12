package cue.edu.co.yousongbackend.sesion;

import cue.edu.co.yousongbackend.control.AuditoriaService;
import cue.edu.co.yousongbackend.control.ResultadoAuditoria;
import cue.edu.co.yousongbackend.establecimiento.EstablecimientoService;
import cue.edu.co.yousongbackend.establecimiento.HorarioSemanal;
import cue.edu.co.yousongbackend.establecimiento.HorarioService;
import cue.edu.co.yousongbackend.shared.persistence.BloqueoTransaccional;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Closes, on its own, the sessions the weekly windows say are over. Runs every {@code app.schedule.tick-seconds}
 * (default 30).
 *
 * <p><b>Rule.</b> A session in operation (ABIERTA, PAUSADA or CERRANDO) is closed when all of these hold:
 * <ol>
 *   <li>its establishment has at least one active weekly window;</li>
 *   <li>the establishment is now outside every window;</li>
 *   <li>the session was opened inside a window. A session the owner opened manually outside every window is left to
 *       the owner and is never closed automatically;</li>
 *   <li>it has been open for at least {@code app.schedule.min-open-seconds} (default 60), so a session opened just
 *       before a window ends is not closed within one tick.</li>
 * </ol>
 * In practice a session opened inside a window closes when that window ends, or when the last of several touching
 * windows ends. The schedule is the current one: replacing it while a session is open applies on the next tick.
 * The system pause and the session pause are no reason to close, and they do not prevent it either.
 *
 * <p><b>How.</b> Each close goes through {@link SesionService#cerrar}, exactly like a manual close (requests
 * finalized, participants revoked so they get SESSION_ENDED, {@code session.closed}, final snapshot on the live
 * queue), plus an audit row {@code SESION_CERRADA_POR_HORARIO}, in its own transaction: one failing establishment
 * is logged (WARN, establishment id only) and the others are still processed.
 *
 * <p><b>Several instances.</b> A tick holds a transaction-level PostgreSQL advisory lock
 * ({@link BloqueoTransaccional.Clave#CIERRE_POR_HORARIO}); an instance that finds it taken skips that tick.
 */
@Component
public class CierrePorHorario {

    private static final Logger log = LoggerFactory.getLogger(CierrePorHorario.class);

    private final JdbcTemplate jdbc;
    private final BloqueoTransaccional bloqueos;
    private final EstablecimientoService establecimientos;
    private final HorarioService horarios;
    private final SesionService sesiones;
    private final AuditoriaService auditoria;
    private final TransactionTemplate tick;
    private final TransactionTemplate cierre;
    private final Clock clock;
    private final Duration minimoAbierta;

    CierrePorHorario(JdbcTemplate jdbc, BloqueoTransaccional bloqueos, EstablecimientoService establecimientos,
                     HorarioService horarios, SesionService sesiones, AuditoriaService auditoria,
                     PlatformTransactionManager transactionManager, Clock clock,
                     @Value("${app.schedule.min-open-seconds:60}") long segundosMinimos) {
        if (segundosMinimos < 0) {
            throw new IllegalStateException("app.schedule.min-open-seconds must not be negative");
        }
        this.jdbc = jdbc;
        this.bloqueos = bloqueos;
        this.establecimientos = establecimientos;
        this.horarios = horarios;
        this.sesiones = sesiones;
        this.auditoria = auditoria;
        this.tick = new TransactionTemplate(transactionManager);
        this.cierre = new TransactionTemplate(transactionManager);
        this.cierre.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.minimoAbierta = Duration.ofSeconds(segundosMinimos);
    }

    @Scheduled(fixedDelayString = "${app.schedule.tick-seconds:30}", initialDelayString = "${app.schedule.tick-seconds:30}",
            timeUnit = TimeUnit.SECONDS)
    void tick() {
        try {
            ejecutarCierresPendientes();
        } catch (RuntimeException e) {
            log.warn("Scheduled session close tick failed: {}", e.getClass().getSimpleName());
        }
    }

    /** One tick: closes every session the rule says is over and returns how many were closed. */
    int ejecutarCierresPendientes() {
        Integer cerradas = tick.execute(estado -> {
            if (!bloqueos.intentar(BloqueoTransaccional.Clave.CIERRE_POR_HORARIO)) {
                return 0;
            }
            int total = 0;
            for (Candidata candidata : candidatas()) {
                try {
                    if (Boolean.TRUE.equals(cierre.execute(nuevo -> cerrarSiTermino(candidata)))) {
                        total++;
                    }
                } catch (RuntimeException e) {
                    log.warn("Scheduled close failed for establishment {}: {}", candidata.establecimientoId(),
                            e.getClass().getSimpleName());
                }
            }
            return total;
        });
        return cerradas == null ? 0 : cerradas;
    }

    private record Candidata(UUID sesionId, UUID establecimientoId, Instant abiertaAt) {
    }

    /** Sessions in operation of establishments with at least one active window. */
    private List<Candidata> candidatas() {
        return jdbc.query("SELECT s.id, s.establecimiento_id, s.abierta_at FROM operacion.sesion s "
                        + "WHERE s.estado IN ('ABIERTA', 'PAUSADA', 'CERRANDO') AND EXISTS (SELECT 1 FROM operacion.horario h "
                        + "WHERE h.establecimiento_id = s.establecimiento_id AND h.activo)",
                (fila, n) -> new Candidata(fila.getObject("id", UUID.class), fila.getObject("establecimiento_id", UUID.class),
                        fila.getTimestamp("abierta_at").toInstant()));
    }

    private boolean cerrarSiTermino(Candidata candidata) {
        HorarioSemanal horario = horarios.horarioDe(establecimientos.obtener(candidata.establecimientoId()));
        Instant ahora = clock.instant();
        boolean termino = horario.restringe()
                && !horario.abiertoEn(ahora)
                && horario.abiertoEn(candidata.abiertaAt())
                && !Duration.between(candidata.abiertaAt(), ahora).minus(minimoAbierta).isNegative();
        if (!termino) {
            return false;
        }
        sesiones.cerrar(candidata.sesionId());
        auditoria.registrar(candidata.establecimientoId(), "SESION_CERRADA_POR_HORARIO", "SESION", candidata.sesionId(),
                ResultadoAuditoria.EXITO);
        return true;
    }
}
