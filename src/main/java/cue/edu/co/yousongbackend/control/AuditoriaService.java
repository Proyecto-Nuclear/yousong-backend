package cue.edu.co.yousongbackend.control;

import cue.edu.co.yousongbackend.establecimiento.PropietarioActual;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records owner and system actions in {@code operacion.auditoria}, inside the transaction of the action itself,
 * so an action and its audit row are committed (or rolled back) together.
 *
 * <p>The actor is pseudonymous: an establishment has exactly one owner, so the row's {@code establecimiento_id}
 * identifies who acted without storing the owner's identity-provider subject, e-mail or name.
 * {@code personal_id} stays NULL (owners are not staff rows). Automatic actions use the explicit overload.
 */
@Service
public class AuditoriaService {

    private final PropietarioActual propietario;
    private final EntityManager entityManager;
    private final Clock clock;

    AuditoriaService(PropietarioActual propietario, EntityManager entityManager, Clock clock) {
        this.propietario = propietario;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    /** An action of the owner behind the current request, on their own establishment. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(String accion, String entidadTipo, UUID entidadRef, ResultadoAuditoria resultado) {
        registrar(propietario.establecimientoId(), accion, entidadTipo, entidadRef, resultado);
    }

    /** An action on the given establishment, for callers without an owner request (e.g. scheduled jobs). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(UUID establecimientoId, String accion, String entidadTipo, UUID entidadRef,
                          ResultadoAuditoria resultado) {
        registrar(establecimientoId, accion, entidadTipo, entidadRef, resultado, UUID.randomUUID());
    }

    /** Same, sharing the correlation id of the events the action published, so they can be joined later. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void registrar(UUID establecimientoId, String accion, String entidadTipo, UUID entidadRef,
                          ResultadoAuditoria resultado, UUID correlacionId) {
        Auditoria auditoria = new Auditoria();
        auditoria.setId(UUID.randomUUID());
        auditoria.setEstablecimientoId(establecimientoId);
        auditoria.setAccionCodigo(accion);
        auditoria.setEntidadTipo(entidadTipo);
        auditoria.setEntidadRef(entidadRef);
        auditoria.setResultado(resultado);
        auditoria.setCorrelacionId(correlacionId);
        auditoria.setRegistradaAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
        entityManager.persist(auditoria);
    }
}
