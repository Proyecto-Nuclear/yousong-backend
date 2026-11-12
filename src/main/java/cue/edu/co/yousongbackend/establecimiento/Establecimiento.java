package cue.edu.co.yousongbackend.establecimiento;

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

/** Maps operacion.establecimiento. */
@Entity
@Table(schema = "operacion", name = "establecimiento")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Establecimiento {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "nombre", nullable = false, length = 120)
    private String nombre;

    @Column(name = "qr_slug", nullable = false, length = 80)
    private String qrSlug;

    @Column(name = "zona_horaria", nullable = false, length = 64)
    private String zonaHoraria = "America/Bogota";

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    @Column(name = "creado_at", nullable = false)
    private Instant creadoAt = Instant.now();

    /** Identity-provider subject of the owner; NULL for establishments created before ownership existed. Never exposed. */
    @Column(name = "propietario_sub", length = 64)
    private String propietarioSub;
}
