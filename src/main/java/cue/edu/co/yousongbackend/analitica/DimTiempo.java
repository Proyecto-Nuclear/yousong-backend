package cue.edu.co.yousongbackend.analitica;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Analytic time dimension (hourly grain, id is YYYYMMDDHH). */
@Entity
@Table(schema = "analitica", name = "dim_tiempo")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class DimTiempo {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private int id;

    @Column(name = "fecha_local", nullable = false)
    private LocalDate fechaLocal;

    @Column(name = "hora", nullable = false)
    private short hora;

    @Column(name = "dia_semana", nullable = false)
    private short diaSemana;
}
