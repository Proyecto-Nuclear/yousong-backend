package cue.edu.co.yousongbackend.analitica;

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

/** Publication marker of a session in a batch. */
@Entity
@Table(schema = "analitica", name = "publicacion_sesion")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class PublicacionSesion {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "sesion_id", nullable = false, updatable = false)
    private UUID sesionId;

    @Column(name = "lote_id", nullable = false)
    private UUID loteId;

    @Column(name = "version", nullable = false)
    private long version = 0;

    @Column(name = "publicada_at", nullable = false)
    private Instant publicadaAt;

    @Column(name = "corte_eventos_at", nullable = false)
    private Instant corteEventosAt;
}
