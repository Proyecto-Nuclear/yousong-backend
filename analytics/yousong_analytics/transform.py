"""Dimensions, facts and state reconciliation of the analytic model (PySpark).

Facts are rebuilt per session from the operational snapshot, so a reprocessed session yields the same rows:
- hecho_solicitud: one row per operacion.solicitud; estado is the operational state; primer_inicio_at is the
  earliest PLAYING (iniciada_at) among its attempts, NULL if none started; espera_real_s = primer_inicio_at -
  creada_at, NULL (not zero) when the request never started.
- hecho_voto: one row per operacion.voto (es_inicial included once).
- hecho_reproduccion: one row per attempt; resultado NULL while in progress; iniciada_at copied as observed, so a
  failure before PLAYING keeps iniciada_at NULL.
"""

from __future__ import annotations

from dataclasses import dataclass

from pyspark.sql import DataFrame, Window
from pyspark.sql import functions as F
from pyspark.sql.types import StringType

from .ids import dim_cancion_id, dim_participacion_id
from .schemas import Snapshot

REQUEST_STATES = ("PENDIENTE", "RESERVADA", "REPRODUCIENDO", "ATENDIDA", "SALTADA", "FALLIDA", "BLOQUEADA",
                  "NO_ATENDIDA_CIERRE")
PLAYBACK_RESULTS = ("COMPLETADA", "SALTADA", "FALLIDA", "INTERRUMPIDA_CIERRE")
SESSION_STATES = ("ABIERTA", "PAUSADA", "CERRANDO", "CERRADA")
CHANNELS = ("WEB", "ANDROID")

_cancion_key = F.udf(lambda est, ref: dim_cancion_id(est, ref), StringType())
_participacion_key = F.udf(lambda ses, ref: dim_participacion_id(ses, ref), StringType())

TABLES = ("dim_establecimiento", "dim_sesion", "dim_tiempo", "dim_cancion", "dim_lista", "dim_participacion",
          "hecho_solicitud", "hecho_voto", "hecho_reproduccion")


def _local(df: DataFrame, ts: str) -> DataFrame:
    """Adds the local calendar hour of ``ts`` (needs a ``zona_horaria`` column)."""
    local = F.from_utc_timestamp(F.col(ts), F.col("zona_horaria"))
    return (df.withColumn("_local", local)
            .withColumn("tiempo_id", F.date_format("_local", "yyyyMMddHH").cast("int"))
            .withColumn("fecha_local", F.to_date("_local"))
            .withColumn("hora", F.hour("_local").cast("smallint"))
            # Spark: Sunday=1..Saturday=7; ISO: Monday=1..Sunday=7.
            .withColumn("dia_semana", (((F.dayofweek("_local") + 5) % 7) + 1).cast("smallint"))
            .drop("_local"))


def build_model(snapshot: Snapshot) -> dict[str, DataFrame]:
    sesion_tz = (snapshot.sesion.alias("s")
                 .join(snapshot.establecimiento.alias("e"), F.col("s.establecimiento_id") == F.col("e.id"))
                 .select(F.col("s.id").alias("sesion_id"), F.col("s.establecimiento_id"), F.col("e.zona_horaria")))

    dim_establecimiento = snapshot.establecimiento.select("id", "nombre", "zona_horaria")
    dim_sesion = snapshot.sesion.select("id", "establecimiento_id", "fecha_operativa", "estado", "abierta_at",
                                        "cerrada_at")
    dim_lista = snapshot.lista.select("id", "establecimiento_id", "nombre", "version")
    dim_participacion = snapshot.participacion.select(
        _participacion_key("sesion_id", "participante_ref").alias("id"), "sesion_id", "participante_ref", "canal",
        "ingreso_at")

    used = (snapshot.solicitud.select("sesion_id", "cancion_id")
            .unionByName(snapshot.voto.select("sesion_id", "cancion_id"))
            .unionByName(snapshot.reproduccion.select("sesion_id", "cancion_id"))
            .join(sesion_tz, "sesion_id").select("establecimiento_id", "cancion_id").distinct())
    dim_cancion = (used.join(snapshot.cancion, used.cancion_id == snapshot.cancion.id)
                   .select(_cancion_key("establecimiento_id", "cancion_id").alias("id"), "establecimiento_id",
                           F.col("cancion_id").alias("cancion_ref"), "genero_propio", "fuente_clasificacion"))

    first_start = (snapshot.reproduccion.filter(F.col("solicitud_id").isNotNull())
                   .groupBy("solicitud_id").agg(F.min("iniciada_at").alias("primer_inicio_at")))
    solicitudes = _local(snapshot.solicitud.join(sesion_tz, "sesion_id"), "creada_at") \
        .join(first_start, F.col("id") == F.col("solicitud_id"), "left")
    wait = (F.unix_micros("primer_inicio_at") - F.unix_micros("creada_at")) / F.lit(1_000_000)
    hecho_solicitud = solicitudes.select(
        F.col("id").alias("solicitud_ref"), "sesion_id",
        _cancion_key("establecimiento_id", "cancion_id").alias("cancion_id"),
        _participacion_key("sesion_id", "creador_ref").alias("participacion_id"),
        "tiempo_id", "creada_at", "primer_inicio_at", "estado",
        F.when(F.col("primer_inicio_at").isNull(), F.lit(None)).otherwise(wait).cast("decimal(12,3)")
        .alias("espera_real_s"))

    votos = _local(snapshot.voto.join(sesion_tz, "sesion_id"), "creado_at")
    hecho_voto = votos.select(
        F.col("id").alias("voto_ref"), F.col("solicitud_id").alias("solicitud_ref"), "sesion_id",
        _cancion_key("establecimiento_id", "cancion_id").alias("cancion_id"),
        _participacion_key("sesion_id", "participante_ref").alias("participacion_id"),
        "tiempo_id", "creado_at", "es_inicial")

    hecho_reproduccion = snapshot.reproduccion.join(sesion_tz, "sesion_id").select(
        F.col("id").alias("reproduccion_ref"), "sesion_id",
        _cancion_key("establecimiento_id", "cancion_id").alias("cancion_id"), "origen",
        F.col("solicitud_id").alias("solicitud_ref"), "lista_id", "preparada_at", "iniciada_at", "terminada_at",
        "resultado")

    tiempo_cols = ["tiempo_id", "fecha_local", "hora", "dia_semana"]
    dim_tiempo = (solicitudes.select(*tiempo_cols).unionByName(votos.select(*tiempo_cols)).distinct()
                  .withColumnRenamed("tiempo_id", "id"))

    return {"dim_establecimiento": dim_establecimiento, "dim_sesion": dim_sesion, "dim_tiempo": dim_tiempo,
            "dim_cancion": dim_cancion, "dim_lista": dim_lista, "dim_participacion": dim_participacion,
            "hecho_solicitud": hecho_solicitud, "hecho_voto": hecho_voto, "hecho_reproduccion": hecho_reproduccion}


@dataclass(frozen=True)
class Failure:
    """A session that cannot be published in this batch, and why."""
    sesion_id: str
    regla_codigo: str
    campo_ruta: str
    registro: str | None


def _failures(df: DataFrame, rule: str, path: str, record: str) -> list[Failure]:
    return [Failure(r["sesion_id"], rule, path, r["registro"])
            for r in df.select("sesion_id", F.col(record).cast("string").alias("registro")).collect()]


def _domain(df: DataFrame, column: str, values: tuple[str, ...], nullable: bool = False) -> F.Column:
    bad = ~F.col(column).isin(*values)
    return (bad & F.col(column).isNotNull()) if nullable else (bad | F.col(column).isNull())


def reconcile(model: dict[str, DataFrame]) -> list[Failure]:
    """Domain re-checks and request/playback state reconciliation. Empty list: every session reconciles."""
    s, r = model["hecho_solicitud"], model["hecho_reproduccion"]
    sesion, part = model["dim_sesion"], model["dim_participacion"]
    out: list[Failure] = []

    # Domains and nulls, checked again on the data that will be published (read back from Parquet).
    out += _failures(s.filter(_domain(s, "estado", REQUEST_STATES)), "DOMINIO_INVALIDO", "hecho_solicitud.estado",
                     "solicitud_ref")
    out += _failures(s.filter(F.col("espera_real_s") < 0), "ESPERA_NEGATIVA", "hecho_solicitud.espera_real_s",
                     "solicitud_ref")
    out += _failures(r.filter(_domain(r, "resultado", PLAYBACK_RESULTS, nullable=True)), "DOMINIO_INVALIDO",
                     "hecho_reproduccion.resultado", "reproduccion_ref")
    exclusive = (((F.col("origen") == "SOLICITUD") & F.col("solicitud_ref").isNotNull() & F.col("lista_id").isNull())
                 | ((F.col("origen") == "DEFECTO") & F.col("lista_id").isNotNull() & F.col("solicitud_ref").isNull()))
    out += _failures(r.filter(~exclusive), "ORIGEN_INCOHERENTE", "hecho_reproduccion.origen", "reproduccion_ref")
    out += _failures(r.filter((F.col("resultado") == "COMPLETADA") & F.col("iniciada_at").isNull()),
                     "COMPLETADA_SIN_INICIO", "hecho_reproduccion.iniciada_at", "reproduccion_ref")
    out += _failures(r.filter(F.col("resultado").isNull() != F.col("terminada_at").isNull()),
                     "RESULTADO_SIN_CIERRE", "hecho_reproduccion.terminada_at", "reproduccion_ref")
    out += _failures(sesion.withColumnRenamed("id", "sesion_id").filter(_domain(sesion, "estado", SESSION_STATES)),
                     "DOMINIO_INVALIDO", "dim_sesion.estado", "sesion_id")
    out += _failures(part.filter(_domain(part, "canal", CHANNELS)), "DOMINIO_INVALIDO", "dim_participacion.canal",
                     "id")
    out += _failures(part.filter(~F.col("participante_ref").rlike("^[0-9a-f]{64}$")), "DOMINIO_INVALIDO",
                     "dim_participacion.participante_ref", "id")

    # An attended request needs a completed attempt.
    completed = r.filter(F.col("resultado") == "COMPLETADA").select("solicitud_ref").distinct()
    attended = s.filter(F.col("estado") == "ATENDIDA").join(completed, "solicitud_ref", "left_anti")
    out += _failures(attended, "CONCILIACION_ATENDIDA", "hecho_solicitud.estado", "solicitud_ref")

    # The latest attempt of a request explains its terminal state.
    latest = (r.filter(F.col("solicitud_ref").isNotNull())
              .withColumn("_n", F.row_number().over(Window.partitionBy("solicitud_ref")
                                                    .orderBy(F.desc("preparada_at"), F.desc("reproduccion_ref"))))
              .filter(F.col("_n") == 1).select("solicitud_ref", "resultado"))
    pairs = s.select("solicitud_ref", "sesion_id", "estado").join(latest, "solicitud_ref")
    mismatch = (((F.col("resultado") == "COMPLETADA") & (F.col("estado") != "ATENDIDA"))
                | ((F.col("resultado") == "SALTADA") & (F.col("estado") != "SALTADA"))
                | ((F.col("resultado") == "INTERRUMPIDA_CIERRE") & (F.col("estado") != "NO_ATENDIDA_CIERRE"))
                | ((F.col("resultado") == "FALLIDA") & (F.col("estado") == "ATENDIDA")))
    out += _failures(pairs.filter(mismatch), "CONCILIACION_RESULTADO", "hecho_solicitud.estado", "solicitud_ref")

    # A playing request has its open attempt; a closed session has none.
    open_attempts = r.filter(F.col("resultado").isNull()).select("solicitud_ref").distinct()
    playing = s.filter(F.col("estado") == "REPRODUCIENDO").join(open_attempts, "solicitud_ref", "left_anti")
    out += _failures(playing, "CONCILIACION_REPRODUCIENDO", "hecho_solicitud.estado", "solicitud_ref")
    closed = sesion.filter(F.col("estado") == "CERRADA").select(F.col("id").alias("sesion_id"))
    dangling = r.filter(F.col("resultado").isNull()).join(closed, "sesion_id")
    out += _failures(dangling, "CONCILIACION_INTENTO_ABIERTO", "hecho_reproduccion.resultado", "reproduccion_ref")
    return out
