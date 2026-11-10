package cue.edu.co.yousongbackend.acceso;

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

/** Maps operacion.participacion. */
@Entity
@Table(schema = "operacion", name = "participacion")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Participacion {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "sesion_id", nullable = false)
    private UUID sesionId;

    @Column(name = "dispositivo_id")
    private UUID dispositivoId;

    @Column(name = "seudonimo", nullable = false, length = 32)
    private String seudonimo;

    @Column(name = "ingreso_at", nullable = false)
    private Instant ingresoAt = Instant.now();

    @Column(name = "ultima_actividad_at", nullable = false)
    private Instant ultimaActividadAt = Instant.now();
}
