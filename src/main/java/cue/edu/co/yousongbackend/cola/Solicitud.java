package cue.edu.co.yousongbackend.cola;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps operacion.solicitud. */
@Entity
@Table(schema = "operacion", name = "solicitud")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Solicitud {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "sesion_id", nullable = false)
    private UUID sesionId;

    @Column(name = "cancion_id", nullable = false)
    private UUID cancionId;

    @Column(name = "creador_id", nullable = false)
    private UUID creadorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 24)
    private EstadoSolicitud estado = EstadoSolicitud.PENDIENTE;

    @Column(name = "ciclo", nullable = false)
    private int ciclo = 1;

    @Column(name = "creada_at", nullable = false)
    private Instant creadaAt = Instant.now();

    @Column(name = "finalizada_at")
    private Instant finalizadaAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version = 0;
}
