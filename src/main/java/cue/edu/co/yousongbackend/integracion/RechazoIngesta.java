package cue.edu.co.yousongbackend.integracion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.time.Instant;
import java.util.UUID;

/** Record rejected during analytic ingestion. */
@Entity
@Table(schema = "integracion", name = "rechazo_ingesta")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class RechazoIngesta {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "lote_id", nullable = false)
    private UUID loteId;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "regla_codigo", nullable = false, length = 60)
    private String reglaCodigo;

    @Column(name = "campo_ruta", length = 120)
    private String campoRuta;

    @Column(name = "detectado_at", nullable = false)
    private Instant detectadoAt = Instant.now();

    @Column(name = "registro_ref", length = 300)
    private String registroRef;
}
