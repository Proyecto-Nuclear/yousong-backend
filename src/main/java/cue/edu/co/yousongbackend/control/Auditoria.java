package cue.edu.co.yousongbackend.control;

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

/** Maps operacion.auditoria. */
@Entity
@Table(schema = "operacion", name = "auditoria")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Auditoria {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "establecimiento_id")
    private UUID establecimientoId;

    @Column(name = "personal_id")
    private UUID personalId;

    @Column(name = "accion_codigo", nullable = false, length = 60)
    private String accionCodigo;

    @Column(name = "entidad_tipo", nullable = false, length = 50)
    private String entidadTipo;

    @Column(name = "entidad_ref")
    private UUID entidadRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "resultado", nullable = false, length = 12)
    private ResultadoAuditoria resultado;

    @Column(name = "correlacion_id", nullable = false)
    private UUID correlacionId;

    @Column(name = "registrada_at", nullable = false)
    private Instant registradaAt = Instant.now();
}
