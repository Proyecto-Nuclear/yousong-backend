package cue.edu.co.yousongbackend.repertorio;

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

/** Maps operacion.lista_defecto. */
@Entity
@Table(schema = "operacion", name = "lista_defecto")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ListaDefecto {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "establecimiento_id", nullable = false)
    private UUID establecimientoId;

    @Column(name = "nombre", nullable = false, length = 100)
    private String nombre;

    @Column(name = "version", nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(name = "politica_rotacion", nullable = false, length = 20)
    private PoliticaRotacion politicaRotacion = PoliticaRotacion.SECUENCIAL;

    @Column(name = "creado_at", nullable = false)
    private Instant creadoAt = Instant.now();
}
