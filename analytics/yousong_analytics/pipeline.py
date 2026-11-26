"""One pipeline cycle and the 5-minute loop."""

from __future__ import annotations

import logging
import signal
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Callable

import psycopg
from pyspark.sql import SparkSession
from pyspark.sql import functions as F

from . import publish as db
from .config import Config
from .extract import read_batch
from .storage import Storage
from .transform import build_model, reconcile
from .validate import classify

log = logging.getLogger("yousong_analytics")


@dataclass
class RunResult:
    lote_id: str | None
    estado: str  # PUBLICADO, VALIDADO (nothing to publish), FALLIDO, OCUPADO (another run holds the lock)
    counters: dict[str, int] = field(default_factory=dict)
    publicadas: list[str] = field(default_factory=list)
    no_conciliadas: list[str] = field(default_factory=list)


def run_once(config: Config, spark: SparkSession,
             before_commit: Callable[[psycopg.Connection], None] | None = None) -> RunResult:
    storage = Storage(config.parquet_dir)
    with db.connect(config.db_url, config.db_user, config.db_password) as conn:
        if not db.try_lock(conn):
            log.info("Another pipeline run holds the lock; skipping this cycle")
            return RunResult(None, "OCUPADO")
        lote_id = None
        try:
            closed = db.fail_unfinished(conn)
            if closed:
                log.warning("Closed %d unfinished batch(es) of an interrupted run as FALLIDO", closed)

            batch = read_batch(conn, config.hmac_key, config.lag_seconds)
            new_id = str(uuid.uuid4())
            ruta = Storage.raw_dir(new_id, batch.rango_hasta)
            lote_id = db.create_lote(conn, batch.rango_desde, batch.rango_hasta, len(batch.events), ruta, new_id)

            events, snapshot = storage.write_raw(spark, ruta, batch.events, batch.snapshot)
            classification = classify(spark, events, snapshot)

            model = storage.write_curated(spark, lote_id, build_model(snapshot))
            failures = reconcile(model)
            storage.write_quarantine(spark, lote_id, classification.rejections)
            db.mark_validated(conn, lote_id, classification.counters, classification.rejections, failures)

            rejected_sessions = sorted({f.sesion_id for f in failures})
            sesiones = [s for s in batch.sesiones if s not in rejected_sessions]
            result = RunResult(lote_id, "VALIDADO", classification.counters, sesiones, rejected_sessions)
            if not sesiones:
                db.finish_without_publication(conn, lote_id)
            else:
                db.publish(conn, lote_id, batch.rango_hasta, sesiones, _tables(model, sesiones), before_commit)
                result.estado = "PUBLICADO"
            log.info("Batch %s %s: %s; sessions published=%d not reconciled=%d", lote_id, result.estado,
                     classification.counters, len(sesiones), len(rejected_sessions))
            return result
        except Exception:
            log.exception("Batch %s failed; the previous publication stays visible", lote_id)
            if lote_id is not None:
                try:
                    db.mark_failed(conn, lote_id)
                except psycopg.Error:
                    log.exception("Could not mark batch %s as FALLIDO", lote_id)
            return RunResult(lote_id, "FALLIDO")
        finally:
            try:
                db.unlock(conn)
            except psycopg.Error:
                pass


def _tables(model, sesiones: list[str]) -> dict[str, list[dict]]:
    """Rows to publish, limited to reconciled sessions and the dimensions they use."""
    def collect(name, condition=None):
        df = model[name] if condition is None else model[name].filter(condition)
        return db.plain_rows(df.collect())

    in_sessions = F.col("sesion_id").isin(sesiones)
    tables = {name: collect(name, in_sessions) for name in
              ("dim_participacion", "hecho_solicitud", "hecho_voto", "hecho_reproduccion")}
    tables["dim_sesion"] = collect("dim_sesion", F.col("id").isin(sesiones))
    establishments = sorted({s["establecimiento_id"] for s in tables["dim_sesion"]})
    tables["dim_establecimiento"] = collect("dim_establecimiento", F.col("id").isin(establishments))
    used_songs = sorted({r["cancion_id"] for t in db.FACTS for r in tables[t]})
    tables["dim_cancion"] = collect("dim_cancion", F.col("id").isin(used_songs))
    used_lists = sorted({r["lista_id"] for r in tables["hecho_reproduccion"] if r["lista_id"]})
    tables["dim_lista"] = collect("dim_lista", F.col("id").isin(used_lists))
    used_hours = sorted({r["tiempo_id"] for t in ("hecho_solicitud", "hecho_voto") for r in tables[t]})
    tables["dim_tiempo"] = collect("dim_tiempo", F.col("id").isin(used_hours))
    return tables


def run_loop(config: Config, spark: SparkSession, stop: threading.Event | None = None) -> None:
    """Runs a cycle every ``interval_seconds`` until SIGTERM/SIGINT. Closed sessions are picked up by the next
    cycle after their close (see extract.SESSIONS)."""
    stop = stop or threading.Event()
    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: stop.set())
    while not stop.is_set():
        started = time.monotonic()
        run_once(config, spark)
        stop.wait(max(0.0, config.interval_seconds - (time.monotonic() - started)))
    log.info("Pipeline loop stopped")
