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
import cue.edu.co.yousongbackend.acceso.CanalAcceso;

/** Analytic dimension for a pseudonymous participant in a session. */
@Entity
@Table(schema = "analitica", name = "dim_participacion")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class DimParticipacion {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "sesion_id", nullable = false)
    private UUID sesionId;

    @Column(name = "participante_ref", nullable = false, length = 64)
    private String participanteRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "canal", nullable = false, length = 12)
    private CanalAcceso canal;

    @Column(name = "ingreso_at", nullable = false)
    private Instant ingresoAt;
}
