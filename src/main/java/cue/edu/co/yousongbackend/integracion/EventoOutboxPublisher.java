package cue.edu.co.yousongbackend.integracion;

import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Writes a confirmed business fact to the outbox. It only runs inside the transaction of the use case
 * that produced the fact, so the fact and its event are committed (or rolled back) together.
 */
@Component
public class EventoOutboxPublisher {

    private final EntityManager entityManager;
    private final JsonMapper json;
    private final Clock clock;

    EventoOutboxPublisher(EntityManager entityManager, JsonMapper json, Clock clock) {
        this.entityManager = entityManager;
        this.json = json;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void publicar(String eventType, AmbitoEvento ambito, String productor, UUID correlacionId,
                         UUID establecimientoId, UUID sesionId,
                         String agregadoTipo, UUID agregadoId, long agregadoVersion,
                         Map<String, Object> payload) {
        EventoOutbox evento = new EventoOutbox();
        evento.setEventId(UUID.randomUUID());
        evento.setEventType(eventType);
        evento.setOccurredAt(clock.instant().truncatedTo(ChronoUnit.MICROS));
        evento.setEstablecimientoId(establecimientoId);
        evento.setSesionId(sesionId);
        evento.setAgregadoTipo(agregadoTipo);
        evento.setAgregadoId(agregadoId);
        evento.setAgregadoVersion(agregadoVersion);
        evento.setCorrelacionId(correlacionId);
        evento.setProductor(productor);
        evento.setAmbito(ambito);
        evento.setPayload(json.writeValueAsString(payload));
        entityManager.persist(evento);
    }

    /**
     * Next version for events of one type about one aggregate, for aggregates that have no version column
     * of their own. The caller must serialize concurrent changes to the aggregate (e.g. with a row lock).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long siguienteVersion(String agregadoTipo, UUID agregadoId, String eventType) {
        Long maxima = entityManager.createQuery("""
                select max(e.agregadoVersion) from EventoOutbox e
                 where e.agregadoTipo = :tipo and e.agregadoId = :id and e.eventType = :evento
                """, Long.class)
                .setParameter("tipo", agregadoTipo)
                .setParameter("id", agregadoId)
                .setParameter("evento", eventType)
                .getSingleResult();
        return maxima == null ? 1 : maxima + 1;
    }
}
