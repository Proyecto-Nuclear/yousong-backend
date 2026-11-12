package cue.edu.co.yousongbackend.analitica;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import cue.edu.co.yousongbackend.reproduccion.OrigenReproduccion;
import cue.edu.co.yousongbackend.reproduccion.ResultadoReproduccion;

/** Fact: a playback. */
@Entity
@Table(schema = "analitica", name = "hecho_reproduccion")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class HechoReproduccion {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "reproduccion_ref", nullable = false, updatable = false)
    private UUID reproduccionRef;

    @Column(name = "sesion_id", nullable = false)
    private UUID sesionId;

    @Column(name = "cancion_id", nullable = false)
    private UUID cancionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origen", nullable = false, length = 12)
    private OrigenReproduccion origen;

    @Column(name = "solicitud_ref")
    private UUID solicitudRef;

    @Column(name = "lista_id")
    private UUID listaId;

    @Column(name = "preparada_at", nullable = false)
    private Instant preparadaAt;

    @Column(name = "iniciada_at")
    private Instant iniciadaAt;

    @Column(name = "terminada_at")
    private Instant terminadaAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "resultado", length = 24)
    private ResultadoReproduccion resultado;

    @Column(name = "lote_id", nullable = false)
    private UUID loteId;
}
