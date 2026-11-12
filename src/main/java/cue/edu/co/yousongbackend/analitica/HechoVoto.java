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

/** Fact: a vote on a request. */
@Entity
@Table(schema = "analitica", name = "hecho_voto")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class HechoVoto {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "voto_ref", nullable = false, updatable = false)
    private UUID votoRef;

    @Column(name = "solicitud_ref", nullable = false)
    private UUID solicitudRef;

    @Column(name = "sesion_id", nullable = false)
    private UUID sesionId;

    @Column(name = "cancion_id", nullable = false)
    private UUID cancionId;

    @Column(name = "participacion_id", nullable = false)
    private UUID participacionId;

    @Column(name = "tiempo_id", nullable = false)
    private int tiempoId;

    @Column(name = "creado_at", nullable = false)
    private Instant creadoAt;

    @Column(name = "es_inicial", nullable = false)
    private boolean esInicial;

    @Column(name = "lote_id", nullable = false)
    private UUID loteId;
}
