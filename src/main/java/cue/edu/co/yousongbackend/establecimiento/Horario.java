package cue.edu.co.yousongbackend.establecimiento;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalTime;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps operacion.horario. */
@Entity
@Table(schema = "operacion", name = "horario")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Horario {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "establecimiento_id", nullable = false)
    private UUID establecimientoId;

    @Column(name = "dia_semana", nullable = false)
    private short diaSemana;

    @Column(name = "inicio", nullable = false)
    private LocalTime inicio;

    @Column(name = "fin", nullable = false)
    private LocalTime fin;

    @Column(name = "cruza_medianoche", nullable = false)
    private boolean cruzaMedianoche = false;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;
}
