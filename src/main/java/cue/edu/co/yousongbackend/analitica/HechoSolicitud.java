package cue.edu.co.yousongbackend.analitica;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import cue.edu.co.yousongbackend.cola.EstadoSolicitud;

/** Fact: a song request. */
@Entity
@Table(schema = "analitica", name = "hecho_solicitud")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class HechoSolicitud {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "solicitud_ref", nullable = false, updatable = false)
    private UUID solicitudRef;

    @Column(name = "sesion_id", nullable = false)
    private UUID sesionId;

    @Column(name = "cancion_id", nullable = false)
    private UUID cancionId;

    @Column(name = "participacion_id", nullable = false)
    private UUID participacionId;

    @Column(name = "tiempo_id", nullable = false)
    private int tiempoId;

    @Column(name = "creada_at", nullable = false)
    private Instant creadaAt;

    @Column(name = "primer_inicio_at")
    private Instant primerInicioAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 24)
    private EstadoSolicitud estado;

    @Column(name = "espera_real_s", precision = 12, scale = 3)
    private BigDecimal esperaRealS;

    @Column(name = "lote_id", nullable = false)
    private UUID loteId;
}
