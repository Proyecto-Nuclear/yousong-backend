package cue.edu.co.yousongbackend.reproduccion;

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

/** Maps operacion.reproduccion. */
@Entity
@Table(schema = "operacion", name = "reproduccion")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Reproduccion {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "sesion_id", nullable = false)
    private UUID sesionId;

    @Column(name = "cancion_id", nullable = false)
    private UUID cancionId;

    @Column(name = "estacion_id", nullable = false)
    private UUID estacionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origen", nullable = false, length = 12)
    private OrigenReproduccion origen;

    @Column(name = "solicitud_id")
    private UUID solicitudId;

    @Column(name = "item_lista_id")
    private UUID itemListaId;

    @Column(name = "fencing_token", nullable = false)
    private long fencingToken = 0;

    @Column(name = "preparada_at", nullable = false)
    private Instant preparadaAt = Instant.now();

    @Column(name = "iniciada_at")
    private Instant iniciadaAt;

    @Column(name = "terminada_at")
    private Instant terminadaAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "resultado", length = 24)
    private ResultadoReproduccion resultado;

    @Column(name = "motivo_codigo", length = 60)
    private String motivoCodigo;
}
