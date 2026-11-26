"""Contract validation, reference checks and deduplication of one batch of outbox events (PySpark).

Every record read is classified exactly once, in this order:
1. duplicate delivery (same event_id already seen in the batch)  -> duplicados
2. contract violation (envelope or payload)                     -> rechazados
3. reference to a fact missing from its session                  -> rechazados
4. duplicate fact (same business identity, other event_id)       -> duplicados
5. otherwise                                                      -> aceptados
so leidos = aceptados + duplicados + rechazados (pendientes = 0 once classified).
"""

from __future__ import annotations

from dataclasses import dataclass

from pyspark.sql import DataFrame, SparkSession, Window
from pyspark.sql import functions as F
from pyspark.sql.types import ArrayType, StringType, StructField, StructType

from .contracts import REFERENCES, business_key, validate_event
from .schemas import EVENT_SCHEMA, Snapshot

VIOLATION = StructType([StructField("regla_codigo", StringType(), False),
                        StructField("campo_ruta", StringType(), True)])
FIELDS = EVENT_SCHEMA.fieldNames()


@F.udf(returnType=ArrayType(VIOLATION))
def _violations(row):
    return [(rule, path) for rule, path in validate_event(row.asDict())]


@F.udf(returnType=StringType())
def _business_key(row):
    return business_key(row.asDict())


@dataclass
class Classification:
    accepted: DataFrame
    rejections: list[dict]
    counters: dict[str, int]


def _reference_frame(snapshot: Snapshot) -> DataFrame:
    def part(name: str, df: DataFrame, with_request: bool) -> DataFrame:
        request = F.col("solicitud_id") if with_request else F.lit(None).cast("string")
        return df.select(F.lit(name).alias("ref_tabla"), F.col("id").alias("ref_id"),
                         F.col("sesion_id").alias("ref_sesion"), request.alias("ref_solicitud_id"),
                         F.col("cancion_id").alias("ref_cancion_id"))

    return (part("solicitud", snapshot.solicitud, False)
            .unionByName(part("voto", snapshot.voto, True))
            .unionByName(part("reproduccion", snapshot.reproduccion, True)))


def _reference_violations(df: DataFrame, snapshot: Snapshot) -> DataFrame:
    """Adds ``_viol`` for events whose referenced fact is missing from their session or incoherent."""
    table = F.lit(None).cast("string")
    ref_id = F.lit(None).cast("string")
    id_path = F.lit(None).cast("string")
    for event_type, (name, prop, _) in REFERENCES.items():
        is_type = F.col("event_type") == event_type
        table = F.when(is_type, F.lit(name)).otherwise(table)
        ref_id = F.when(is_type, F.get_json_object("payload_json", f"$.{prop}")).otherwise(ref_id)
        id_path = F.when(is_type, F.lit(f"payload.{prop}")).otherwise(id_path)
    events = (df.withColumn("ref_tabla_e", table).withColumn("ref_id_e", ref_id).withColumn("_id_path", id_path)
              .withColumn("_p_request", F.get_json_object("payload_json", "$.request_id"))
              .withColumn("_p_song", F.get_json_object("payload_json", "$.song_id")))
    refs = _reference_frame(snapshot)
    joined = events.join(refs, (events.ref_tabla_e == refs.ref_tabla) & (events.ref_id_e == refs.ref_id)
                         & (events.sesion_id == refs.ref_sesion), "left")

    missing = F.col("ref_tabla_e").isNotNull() & F.col("ref_tabla").isNull()
    checks = [F.when(missing, F.struct(F.lit("REFERENCIA_INEXISTENTE").alias("regla_codigo"),
                                       F.col("_id_path").alias("campo_ruta")))]
    columns = {"request_id": ("_p_request", "ref_solicitud_id"), "song_id": ("_p_song", "ref_cancion_id")}
    for event_type, (_, _, coherence) in REFERENCES.items():
        for prop in coherence:
            payload_col, ref_col = columns[prop]
            wrong = ((F.col("event_type") == event_type) & F.col("ref_tabla").isNotNull()
                     & ~F.col(payload_col).eqNullSafe(F.col(ref_col)))
            checks.append(F.when(wrong, F.struct(F.lit("REFERENCIA_INCOHERENTE").alias("regla_codigo"),
                                                 F.lit(f"payload.{prop}").alias("campo_ruta"))))
    return joined.withColumn("_viol", F.filter(F.array(*checks), lambda v: v.isNotNull())) \
        .select(*FIELDS, "_pos", "_viol")


def _rejection_rows(df: DataFrame) -> list[dict]:
    rows = (df.select("event_id", "event_type", "occurred_at", "_pos", F.explode("_viol").alias("v"))
            .select("event_id", "event_type", "occurred_at", "_pos", "v.regla_codigo", "v.campo_ruta")
            .collect())
    # One row per (record, rule): the quarantine table is UNIQUE(lote_id, event_id, regla_codigo).
    seen: dict[tuple, dict] = {}
    for row in sorted(rows, key=lambda r: (r["_pos"], r["regla_codigo"], r["campo_ruta"] or "")):
        key = (row["_pos"], row["regla_codigo"])
        if key not in seen:
            seen[key] = {"event_id": row["event_id"], "event_type": row["event_type"],
                         "occurred_at": row["occurred_at"], "regla_codigo": row["regla_codigo"],
                         "campo_ruta": (row["campo_ruta"] or None) and row["campo_ruta"][:120],
                         "posicion": int(row["_pos"])}
    return list(seen.values())


def classify(spark: SparkSession, events: DataFrame, snapshot: Snapshot) -> Classification:
    del spark  # kept for a uniform call shape
    leidos = events.count()
    positioned = events.withColumn("_pos", F.monotonically_increasing_id()).cache()

    by_id = Window.partitionBy("event_id").orderBy("occurred_at", "_pos")
    ranked = positioned.withColumn(
        "_rn", F.when(F.col("event_id").isNull(), F.lit(1)).otherwise(F.row_number().over(by_id)))
    delivery_duplicates = ranked.filter(F.col("_rn") > 1).count()

    checked = (ranked.filter(F.col("_rn") == 1)
               .withColumn("_viol", _violations(F.struct(*[F.col(c) for c in FIELDS])))
               .select(*FIELDS, "_pos", "_viol").cache())
    contract_rejected = checked.filter(F.size("_viol") > 0)
    referenced = _reference_violations(checked.filter(F.size("_viol") == 0), snapshot).cache()
    reference_rejected = referenced.filter(F.size("_viol") > 0)

    valid = referenced.filter(F.size("_viol") == 0) \
        .withColumn("business_key", _business_key(F.struct(*[F.col(c) for c in FIELDS])))
    by_fact = Window.partitionBy("business_key").orderBy("occurred_at", "event_id")
    unique = valid.withColumn("_rn", F.row_number().over(by_fact)).cache()
    fact_duplicates = unique.filter(F.col("_rn") > 1).count()
    accepted = unique.filter(F.col("_rn") == 1).select(*FIELDS, "business_key").cache()

    rejections = _rejection_rows(contract_rejected) + _rejection_rows(reference_rejected)
    rechazados = len({r["posicion"] for r in rejections})
    counters = {"leidos": leidos, "aceptados": accepted.count(),
                "duplicados": delivery_duplicates + fact_duplicates, "rechazados": rechazados}
    if counters["leidos"] != counters["aceptados"] + counters["duplicados"] + counters["rechazados"]:
        raise AssertionError(f"Unbalanced classification: {counters}")
    return Classification(accepted=accepted, rejections=rejections, counters=counters)
