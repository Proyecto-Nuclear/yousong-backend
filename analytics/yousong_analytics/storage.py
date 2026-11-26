"""Private Parquet layout under ANALYTICS_PARQUET_DIR (paths stored in the database are relative):

raw/fecha=YYYY-MM-DD/lote=<id>/eventos            B/O outbox copy of the batch (payload_json as text)
raw/fecha=YYYY-MM-DD/lote=<id>/snapshot/<table>   pseudonymized operational snapshot of the affected sessions
curated/lote=<id>/<table>                         dimensions and facts that were validated for publication
quarantine/lote=<id>                              sanitized rejections: identity, rule and path, never payload
"""

from __future__ import annotations

import os
from datetime import datetime

from pyspark.sql import DataFrame, SparkSession
from pyspark.sql.types import LongType, StringType, StructField, StructType, TimestampType

from .schemas import EVENT_SCHEMA, Snapshot, snapshot_from_rows, snapshot_from_parquet

QUARANTINE_SCHEMA = StructType([
    StructField("posicion", LongType(), False), StructField("event_id", StringType(), True),
    StructField("event_type", StringType(), True), StructField("occurred_at", TimestampType(), True),
    StructField("regla_codigo", StringType(), False), StructField("campo_ruta", StringType(), True)])


class Storage:
    def __init__(self, base_dir: str):
        self.base = base_dir.rstrip("/")

    def _abs(self, relative: str) -> str:
        return os.path.join(self.base, relative)

    @staticmethod
    def raw_dir(lote_id: str, corte: datetime) -> str:
        return f"raw/fecha={corte.strftime('%Y-%m-%d')}/lote={lote_id}"

    def write_raw(self, spark: SparkSession, relative: str, events: list[dict],
                  snapshot: dict[str, list[dict]]) -> tuple[DataFrame, Snapshot]:
        """Writes the batch and reads it back: validation always runs on what Parquet holds."""
        names = EVENT_SCHEMA.fieldNames()
        frame = spark.createDataFrame([tuple(e.get(n) for n in names) for e in events], EVENT_SCHEMA)
        frame.write.mode("overwrite").parquet(self._abs(f"{relative}/eventos"))
        for name, df in snapshot_from_rows(spark, snapshot).tables().items():
            df.write.mode("overwrite").parquet(self._abs(f"{relative}/snapshot/{name}"))
        events_back = spark.read.schema(EVENT_SCHEMA).parquet(self._abs(f"{relative}/eventos"))
        return events_back, snapshot_from_parquet(spark, self._abs(f"{relative}/snapshot"))

    def write_curated(self, spark: SparkSession, lote_id: str, model: dict[str, DataFrame]) -> dict[str, DataFrame]:
        out = {}
        for name, df in model.items():
            path = self._abs(f"curated/lote={lote_id}/{name}")
            df.write.mode("overwrite").parquet(path)
            out[name] = spark.read.schema(df.schema).parquet(path)
        return out

    def write_quarantine(self, spark: SparkSession, lote_id: str, rejections: list[dict]) -> str:
        relative = f"quarantine/lote={lote_id}"
        rows = [(r["posicion"], r["event_id"], r["event_type"], r["occurred_at"], r["regla_codigo"],
                 r["campo_ruta"]) for r in rejections]
        spark.createDataFrame(rows, QUARANTINE_SCHEMA).write.mode("overwrite").parquet(self._abs(relative))
        return relative
