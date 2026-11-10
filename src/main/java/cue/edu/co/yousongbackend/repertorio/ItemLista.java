package cue.edu.co.yousongbackend.repertorio;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps operacion.item_lista. */
@Entity
@Table(schema = "operacion", name = "item_lista")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ItemLista {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "lista_id", nullable = false)
    private UUID listaId;

    @Column(name = "cancion_id", nullable = false)
    private UUID cancionId;

    @Column(name = "posicion", nullable = false)
    private int posicion;
}
