package cue.edu.co.yousongbackend.sesion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps operacion.estacion. */
@Entity
@Table(schema = "operacion", name = "estacion")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Estacion {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "establecimiento_id", nullable = false)
    private UUID establecimientoId;

    @Column(name = "sesion_id")
    private UUID sesionId;

    @Column(name = "nombre", nullable = false, length = 80)
    private String nombre;

    @Column(name = "habilitada", nullable = false)
    private boolean habilitada = false;

    @Column(name = "fencing_token", nullable = false)
    private long fencingToken = 0;

    @Column(name = "lease_hasta")
    private Instant leaseHasta;

    @Column(name = "heartbeat_at")
    private Instant heartbeatAt;
}
