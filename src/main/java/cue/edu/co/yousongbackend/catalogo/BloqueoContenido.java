package cue.edu.co.yousongbackend.catalogo;

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

/** Maps operacion.bloqueo_contenido. */
@Entity
@Table(schema = "operacion", name = "bloqueo_contenido")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class BloqueoContenido {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "establecimiento_id", nullable = false)
    private UUID establecimientoId;

    @Column(name = "cancion_id", nullable = false)
    private UUID cancionId;

    @Column(name = "personal_id", nullable = false)
    private UUID personalId;

    @Column(name = "desde_at", nullable = false)
    private Instant desdeAt = Instant.now();

    @Column(name = "hasta_at")
    private Instant hastaAt;

    @Column(name = "levantado_at")
    private Instant levantadoAt;

    @Column(name = "motivo_codigo", nullable = false, length = 40)
    private String motivoCodigo;
}
