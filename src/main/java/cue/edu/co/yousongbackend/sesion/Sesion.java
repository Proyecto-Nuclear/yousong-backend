package cue.edu.co.yousongbackend.sesion;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Maps operacion.sesion. */
@Entity
@Table(schema = "operacion", name = "sesion")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Sesion {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "establecimiento_id", nullable = false)
    private UUID establecimientoId;

    @Column(name = "lista_id", nullable = false)
    private UUID listaId;

    @Column(name = "fecha_operativa", nullable = false)
    private LocalDate fechaOperativa;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 16)
    private EstadoSesion estado = EstadoSesion.ABIERTA;

    @Column(name = "abierta_at", nullable = false)
    private Instant abiertaAt = Instant.now();

    @Column(name = "cerrada_at")
    private Instant cerradaAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version = 0;

    @Column(name = "cola_version", nullable = false)
    private long colaVersion = 0;
}
