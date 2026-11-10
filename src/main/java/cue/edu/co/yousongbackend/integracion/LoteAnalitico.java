package cue.edu.co.yousongbackend.integracion;

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

/** Analytic ingestion batch with reconciliation counters. */
@Entity
@Table(schema = "integracion", name = "lote_analitico")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class LoteAnalitico {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "rango_desde", nullable = false)
    private Instant rangoDesde;

    @Column(name = "rango_hasta", nullable = false)
    private Instant rangoHasta;

    @Column(name = "iniciado_at", nullable = false)
    private Instant iniciadoAt = Instant.now();

    @Column(name = "finalizado_at")
    private Instant finalizadoAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "estado", nullable = false, length = 16)
    private EstadoLote estado = EstadoLote.EN_CURSO;

    @Column(name = "leidos", nullable = false)
    private long leidos = 0;

    @Column(name = "aceptados", nullable = false)
    private long aceptados = 0;

    @Column(name = "duplicados", nullable = false)
    private long duplicados = 0;

    @Column(name = "rechazados", nullable = false)
    private long rechazados = 0;

    @Column(name = "pendientes", nullable = false)
    private long pendientes = 0;

    @Column(name = "ruta_parquet", length = 300)
    private String rutaParquet;

    @Column(name = "contrato_version", nullable = false)
    private int contratoVersion = 1;
}
