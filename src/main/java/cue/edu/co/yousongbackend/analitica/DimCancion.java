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

/** Analytic dimension for a song. */
@Entity
@Table(schema = "analitica", name = "dim_cancion")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class DimCancion {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "establecimiento_id", nullable = false)
    private UUID establecimientoId;

    @Column(name = "cancion_ref", nullable = false)
    private UUID cancionRef;

    @Column(name = "genero_propio", length = 60)
    private String generoPropio;

    @Column(name = "fuente_clasificacion", length = 200)
    private String fuenteClasificacion;
}
