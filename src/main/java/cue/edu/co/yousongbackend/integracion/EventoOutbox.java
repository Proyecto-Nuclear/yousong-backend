package cue.edu.co.yousongbackend.integracion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Transactional outbox event pending publication. */
@Entity
@Table(schema = "integracion", name = "evento_outbox")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class EventoOutbox {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "schema_version", nullable = false)
    private int schemaVersion = 1;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "establecimiento_id")
    private UUID establecimientoId;

    @Column(name = "sesion_id")
    private UUID sesionId;

    @Column(name = "agregado_tipo", nullable = false, length = 40)
    private String agregadoTipo;

    @Column(name = "agregado_id", nullable = false)
    private UUID agregadoId;

    @Column(name = "agregado_version", nullable = false)
    private long agregadoVersion = 0;

    @Column(name = "correlacion_id", nullable = false)
    private UUID correlacionId;

    @Column(name = "causacion_id")
    private UUID causacionId;

    @Column(name = "actor_ref", length = 64)
    private String actorRef;

    @Column(name = "productor", nullable = false, length = 60)
    private String productor;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "ambito", nullable = false, length = 1)
    private AmbitoEvento ambito;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "publicado_at")
    private Instant publicadoAt;

    @Column(name = "intentos_entrega", nullable = false)
    private int intentosEntrega = 0;
}
