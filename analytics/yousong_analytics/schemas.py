"""Spark schemas of the raw inputs. UUIDs are strings, instants UTC timestamps, counters longs."""

from __future__ import annotations

from dataclasses import dataclass

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql.types import (BooleanType, DateType, IntegerType, LongType, StringType, StructField, StructType,
                               TimestampType)


def _schema(*fields: tuple[str, object, bool]) -> StructType:
    return StructType([StructField(name, kind, nullable) for name, kind, nullable in fields])


S, T, D, I, L, B = StringType(), TimestampType(), DateType(), IntegerType(), LongType(), BooleanType()

# The outbox copy keeps identity, type, version, instant and correlation (lineage) plus the payload as JSON text.
# actor_ref (a personal pseudonym) is not needed by the model and is not copied.
EVENT_SCHEMA = _schema(
    ("event_id", S, True), ("event_type", S, True), ("schema_version", I, True), ("occurred_at", T, True),
    ("establecimiento_id", S, True), ("sesion_id", S, True), ("agregado_tipo", S, True), ("agregado_id", S, True),
    ("agregado_version", L, True), ("correlacion_id", S, True), ("causacion_id", S, True), ("productor", S, True),
    ("ambito", S, True), ("payload_json", S, True),
)

# Operational snapshot, already pseudonymized: no operational participation or device id is present.
SNAPSHOT_SCHEMAS = {
    "establecimiento": _schema(("id", S, False), ("nombre", S, False), ("zona_horaria", S, False)),
    "sesion": _schema(("id", S, False), ("establecimiento_id", S, False), ("lista_id", S, False),
                      ("fecha_operativa", D, False), ("estado", S, False), ("abierta_at", T, False),
                      ("cerrada_at", T, True)),
    "lista": _schema(("id", S, False), ("establecimiento_id", S, False), ("nombre", S, False), ("version", I, False)),
    "participacion": _schema(("sesion_id", S, False), ("participante_ref", S, False), ("canal", S, True),
                             ("ingreso_at", T, False)),
    "cancion": _schema(("id", S, False), ("genero_propio", S, True), ("fuente_clasificacion", S, True)),
    "solicitud": _schema(("id", S, False), ("sesion_id", S, False), ("cancion_id", S, False),
                         ("creador_ref", S, False), ("estado", S, False), ("creada_at", T, False)),
    "voto": _schema(("id", S, False), ("solicitud_id", S, False), ("sesion_id", S, False), ("cancion_id", S, False),
                    ("participante_ref", S, False), ("es_inicial", B, False), ("creado_at", T, False)),
    "reproduccion": _schema(("id", S, False), ("sesion_id", S, False), ("cancion_id", S, False),
                            ("origen", S, False), ("solicitud_id", S, True), ("lista_id", S, True),
                            ("preparada_at", T, False), ("iniciada_at", T, True), ("terminada_at", T, True),
                            ("resultado", S, True)),
}


@dataclass
class Snapshot:
    establecimiento: DataFrame
    sesion: DataFrame
    lista: DataFrame
    participacion: DataFrame
    cancion: DataFrame
    solicitud: DataFrame
    voto: DataFrame
    reproduccion: DataFrame

    def tables(self) -> dict[str, DataFrame]:
        return {name: getattr(self, name) for name in SNAPSHOT_SCHEMAS}


def event_dataframe(spark: SparkSession, rows: list[dict]) -> DataFrame:
    names = EVENT_SCHEMA.fieldNames()
    return spark.createDataFrame([tuple(row.get(n) for n in names) for row in rows], EVENT_SCHEMA)


def snapshot_from_rows(spark: SparkSession, rows: dict[str, list[dict]]) -> Snapshot:
    frames = {}
    for name, schema in SNAPSHOT_SCHEMAS.items():
        names = schema.fieldNames()
        frames[name] = spark.createDataFrame([tuple(r.get(n) for n in names) for r in rows.get(name, [])], schema)
    return Snapshot(**frames)


def snapshot_from_parquet(spark: SparkSession, base: str) -> Snapshot:
    return Snapshot(**{name: spark.read.schema(schema).parquet(f"{base}/{name}")
                       for name, schema in SNAPSHOT_SCHEMAS.items()})
