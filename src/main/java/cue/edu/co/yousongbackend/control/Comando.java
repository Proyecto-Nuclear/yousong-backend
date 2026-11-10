package cue.edu.co.yousongbackend.control;

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

/** Maps operacion.comando. */
@Entity
@Table(schema = "operacion", name = "comando")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Comando {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "ambito", nullable = false, length = 160)
    private String ambito;

    @Column(name = "clave", nullable = false)
    private UUID clave;

    @Column(name = "peticion_hash", nullable = false, length = 64)
    private String peticionHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 16)
    private EstadoComando estado = EstadoComando.EN_PROCESO;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "respuesta", columnDefinition = "jsonb")
    private String respuesta;

    @Column(name = "creado_at", nullable = false)
    private Instant creadoAt = Instant.now();

    @Column(name = "expira_at", nullable = false)
    private Instant expiraAt;
}
