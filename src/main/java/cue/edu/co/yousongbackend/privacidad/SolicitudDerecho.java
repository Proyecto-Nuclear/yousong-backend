package cue.edu.co.yousongbackend.privacidad;

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

/** Maps operacion.solicitud_derecho. */
@Entity
@Table(schema = "operacion", name = "solicitud_derecho")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SolicitudDerecho {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "participacion_id")
    private UUID participacionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "tipo", nullable = false, length = 16)
    private TipoSolicitudDerecho tipo;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 16)
    private EstadoSolicitudDerecho estado = EstadoSolicitudDerecho.RECIBIDA;

    @Column(name = "expediente_ref", nullable = false, length = 120)
    private String expedienteRef;

    @Column(name = "recibida_at", nullable = false)
    private Instant recibidaAt = Instant.now();

    @Column(name = "vence_at", nullable = false)
    private Instant venceAt;

    @Column(name = "cerrada_at")
    private Instant cerradaAt;
}
