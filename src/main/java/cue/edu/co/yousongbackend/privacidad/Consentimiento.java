package cue.edu.co.yousongbackend.privacidad;

import cue.edu.co.yousongbackend.acceso.CanalAcceso;
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

/** Maps operacion.consentimiento. */
@Entity
@Table(schema = "operacion", name = "consentimiento")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Consentimiento {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "participacion_id", nullable = false)
    private UUID participacionId;

    @Column(name = "politica_version", nullable = false, length = 20)
    private String politicaVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "finalidad", nullable = false, length = 4)
    private FinalidadConsentimiento finalidad;

    @Enumerated(EnumType.STRING)
    @Column(name = "accion", nullable = false, length = 12)
    private AccionConsentimiento accion;

    @Enumerated(EnumType.STRING)
    @Column(name = "canal", nullable = false, length = 12)
    private CanalAcceso canal;

    @Column(name = "registrado_at", nullable = false)
    private Instant registradoAt = Instant.now();
}
