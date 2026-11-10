package cue.edu.co.yousongbackend.analitica;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Analytic dimension for a venue. */
@Entity
@Table(schema = "analitica", name = "dim_establecimiento")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class DimEstablecimiento {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "nombre", nullable = false, length = 120)
    private String nombre;

    @Column(name = "zona_horaria", nullable = false, length = 64)
    private String zonaHoraria;
}
