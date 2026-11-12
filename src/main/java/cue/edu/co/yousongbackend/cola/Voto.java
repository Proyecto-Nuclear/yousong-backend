package cue.edu.co.yousongbackend.cola;

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

/** Maps operacion.voto. */
@Entity
@Table(schema = "operacion", name = "voto")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Voto {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "solicitud_id", nullable = false)
    private UUID solicitudId;

    @Column(name = "participacion_id", nullable = false)
    private UUID participacionId;

    @Column(name = "sesion_id", nullable = false)
    private UUID sesionId;

    @Column(name = "cancion_id", nullable = false)
    private UUID cancionId;

    @Column(name = "es_inicial", nullable = false)
    private boolean esInicial = false;

    @Column(name = "creado_at", nullable = false)
    private Instant creadoAt = Instant.now();
}
