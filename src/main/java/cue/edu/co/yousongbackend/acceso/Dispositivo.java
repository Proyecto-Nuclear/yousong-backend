package cue.edu.co.yousongbackend.acceso;

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

/** Maps operacion.dispositivo. */
@Entity
@Table(schema = "operacion", name = "dispositivo")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Dispositivo {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(name = "canal", nullable = false, length = 12)
    private CanalAcceso canal;

    @Column(name = "version_acceso", nullable = false)
    private int versionAcceso = 1;

    @Column(name = "creado_at", nullable = false)
    private Instant creadoAt = Instant.now();

    @Column(name = "ultimo_acceso_at", nullable = false)
    private Instant ultimoAccesoAt = Instant.now();

    @Column(name = "expira_at", nullable = false)
    private Instant expiraAt;

    @Column(name = "revocado_at")
    private Instant revocadoAt;
}
