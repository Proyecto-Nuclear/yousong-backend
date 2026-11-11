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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Maps operacion.dispositivo: a technical context with expiry and revocation, identified only by the SHA-256
 * of an opaque random secret the client keeps. No MAC, IMEI, IP, user agent or fingerprint is stored.
 */
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

    /** Lowercase hex SHA-256 of the device secret; {@code null} for devices that can never be identified again. */
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "credencial_hash", length = 64, unique = true)
    private String credencialHash;
}
