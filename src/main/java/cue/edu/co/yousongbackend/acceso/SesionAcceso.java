package cue.edu.co.yousongbackend.acceso;

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

/** Maps operacion.sesion_acceso. */
@Entity
@Table(schema = "operacion", name = "sesion_acceso")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SesionAcceso {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "personal_id")
    private UUID personalId;

    @Column(name = "dispositivo_id")
    private UUID dispositivoId;

    /** The participation a participant credential opens; {@code null} for staff credentials. */
    @Column(name = "participacion_id")
    private UUID participacionId;

    @Column(name = "familia_id", nullable = false)
    private UUID familiaId;

    @Column(name = "refresh_hash", nullable = false, length = 64)
    private String refreshHash;

    @Column(name = "emitida_at", nullable = false)
    private Instant emitidaAt = Instant.now();

    @Column(name = "expira_at", nullable = false)
    private Instant expiraAt;

    @Column(name = "revocada_at")
    private Instant revocadaAt;
}
