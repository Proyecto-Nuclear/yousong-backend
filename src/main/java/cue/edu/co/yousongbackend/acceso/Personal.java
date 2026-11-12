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

/** Maps operacion.personal. */
@Entity
@Table(schema = "operacion", name = "personal")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Personal {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "usuario", nullable = false, length = 80)
    private String usuario;

    @Column(name = "nombre", nullable = false, length = 120)
    private String nombre;

    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Column(name = "activo", nullable = false)
    private boolean activo = true;

    @Column(name = "creado_at", nullable = false)
    private Instant creadoAt = Instant.now();
}
