"""Batch control (integracion.lote_analitico), quarantine and the atomic publication of analitica.*.

The publication of a batch is ONE transaction: dimensions, facts, publicacion_sesion and the PUBLICADO state are
committed together, so a failure leaves the previous publication visible.
"""

from __future__ import annotations

import uuid
from datetime import date, datetime, timezone
from typing import Callable, Iterable

import psycopg

LOCK_KEY = 7_301_202_611  # pg advisory lock: one pipeline run at a time per database

COLUMNS = {
    "dim_establecimiento": ("id", "nombre", "zona_horaria"),
    "dim_sesion": ("id", "establecimiento_id", "fecha_operativa", "estado", "abierta_at", "cerrada_at"),
    "dim_tiempo": ("id", "fecha_local", "hora", "dia_semana"),
    "dim_cancion": ("id", "establecimiento_id", "cancion_ref", "genero_propio", "fuente_clasificacion"),
    "dim_lista": ("id", "establecimiento_id", "nombre", "version"),
    "dim_participacion": ("id", "sesion_id", "participante_ref", "canal", "ingreso_at"),
    "hecho_solicitud": ("solicitud_ref", "sesion_id", "cancion_id", "participacion_id", "tiempo_id", "creada_at",
                        "primer_inicio_at", "estado", "espera_real_s", "lote_id"),
    "hecho_voto": ("voto_ref", "solicitud_ref", "sesion_id", "cancion_id", "participacion_id", "tiempo_id",
                   "creado_at", "es_inicial", "lote_id"),
    "hecho_reproduccion": ("reproduccion_ref", "sesion_id", "cancion_id", "origen", "solicitud_ref", "lista_id",
                           "preparada_at", "iniciada_at", "terminada_at", "resultado", "lote_id"),
}

UPSERT = {
    "dim_establecimiento": "ON CONFLICT (id) DO UPDATE SET nombre = EXCLUDED.nombre, "
                           "zona_horaria = EXCLUDED.zona_horaria",
    "dim_sesion": "ON CONFLICT (id) DO UPDATE SET establecimiento_id = EXCLUDED.establecimiento_id, "
                  "fecha_operativa = EXCLUDED.fecha_operativa, estado = EXCLUDED.estado, "
                  "abierta_at = EXCLUDED.abierta_at, cerrada_at = EXCLUDED.cerrada_at",
    "dim_tiempo": "ON CONFLICT (id) DO NOTHING",
    # Analytic keys are kept by their business UNIQUE: an existing row keeps its id.
    "dim_cancion": "ON CONFLICT (establecimiento_id, cancion_ref) DO UPDATE SET "
                   "genero_propio = EXCLUDED.genero_propio, fuente_clasificacion = EXCLUDED.fuente_clasificacion",
    "dim_lista": "ON CONFLICT (id) DO UPDATE SET nombre = EXCLUDED.nombre, version = EXCLUDED.version",
    "dim_participacion": "ON CONFLICT (sesion_id, participante_ref) DO UPDATE SET canal = EXCLUDED.canal, "
                         "ingreso_at = EXCLUDED.ingreso_at",
}

DIMENSIONS = ("dim_establecimiento", "dim_sesion", "dim_tiempo", "dim_cancion", "dim_lista", "dim_participacion")
FACTS = ("hecho_solicitud", "hecho_voto", "hecho_reproduccion")


def connect(url: str, user: str, password: str) -> psycopg.Connection:
    return psycopg.connect(url, user=user, password=password, autocommit=True,
                           application_name="yousong-analytics")


def try_lock(conn: psycopg.Connection) -> bool:
    return conn.execute("SELECT pg_try_advisory_lock(%s)", (LOCK_KEY,)).fetchone()[0]


def unlock(conn: psycopg.Connection) -> None:
    conn.execute("SELECT pg_advisory_unlock(%s)", (LOCK_KEY,))


# A failed batch must satisfy the reconciliation CHECK: unclassified records leave the count of read records.
_FAIL = """
UPDATE integracion.lote_analitico
   SET estado = 'FALLIDO', finalizado_at = clock_timestamp(),
       leidos = CASE WHEN pendientes > 0 THEN aceptados + duplicados + rechazados ELSE leidos END,
       pendientes = 0
 WHERE finalizado_at IS NULL AND estado IN ('EN_CURSO', 'VALIDADO')
"""


def fail_unfinished(conn: psycopg.Connection) -> int:
    """Closes batches left open by a crashed run (called while holding the lock)."""
    return conn.execute(_FAIL).rowcount


def mark_failed(conn: psycopg.Connection, lote_id: str) -> None:
    conn.execute(_FAIL + " AND id = %s", (lote_id,))


def create_lote(conn: psycopg.Connection, desde: datetime, hasta: datetime, leidos: int, ruta: str,
                lote_id: str | None = None) -> str:
    lote_id = lote_id or str(uuid.uuid4())
    conn.execute("""
        INSERT INTO integracion.lote_analitico (id, rango_desde, rango_hasta, estado, leidos, pendientes,
                                                ruta_parquet, contrato_version)
        VALUES (%s, %s, %s, 'EN_CURSO', %s, %s, %s, 1)""", (lote_id, desde, hasta, leidos, leidos, ruta))
    return lote_id


def mark_validated(conn: psycopg.Connection, lote_id: str, counters: dict[str, int], rejections: list[dict],
                   reconciliation: list) -> None:
    """Stores the classification and the quarantine (kept even if the publication later fails)."""
    with conn.transaction():
        conn.execute("""
            UPDATE integracion.lote_analitico
               SET estado = 'VALIDADO', leidos = %(leidos)s, aceptados = %(aceptados)s,
                   duplicados = %(duplicados)s, rechazados = %(rechazados)s, pendientes = 0
             WHERE id = %(lote)s""", {**counters, "lote": lote_id})
        rows = [(str(uuid.uuid4()), lote_id, r["event_id"], r["regla_codigo"], r["campo_ruta"],
                 f"quarantine/lote={lote_id}/posicion={r['posicion']}") for r in rejections]
        # Reconciliation failures concern operational state, not one event: event_id stays NULL.
        rows += [(str(uuid.uuid4()), lote_id, None, f.regla_codigo, f.campo_ruta[:120],
                  f"quarantine/lote={lote_id}/conciliacion/sesion={f.sesion_id}/registro={f.registro}"[:300])
                 for f in reconciliation]
        if rows:
            with conn.cursor() as cur:
                cur.executemany("""
                    INSERT INTO integracion.rechazo_ingesta (id, lote_id, event_id, regla_codigo, campo_ruta,
                                                             detectado_at, registro_ref)
                    VALUES (%s, %s, %s, %s, %s, clock_timestamp(), %s)""", rows)


def finish_without_publication(conn: psycopg.Connection, lote_id: str) -> None:
    """Nothing reconciled to publish: the batch stays VALIDADO and is closed."""
    conn.execute("UPDATE integracion.lote_analitico SET finalizado_at = clock_timestamp() WHERE id = %s",
                 (lote_id,))


def _db_value(value):
    # Spark returns naive datetimes in the (UTC) session time zone.
    if isinstance(value, datetime) and value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value


def _insert(cur: psycopg.Cursor, table: str, rows: Iterable[dict], suffix: str = "") -> None:
    cols = COLUMNS[table]
    values = [tuple(_db_value(r.get(c)) for c in cols) for r in rows]
    if not values:
        return
    sql = (f"INSERT INTO analitica.{table} ({', '.join(cols)}) VALUES ({', '.join(['%s'] * len(cols))}) "
           f"{suffix}")
    cur.executemany(sql, values)


def _key_map(cur: psycopg.Cursor, table: str, scope: str, business: str, rows: list[dict]) -> dict[str, str]:
    """Expected analytic id -> stored id (they differ only for rows created by another key generator)."""
    if not rows:
        return {}
    scopes = sorted({r[scope] for r in rows})
    stored = {(str(s), str(b)): str(i) for i, s, b in cur.execute(
        f"SELECT id, {scope}, {business} FROM analitica.{table} WHERE {scope} = ANY(%s::uuid[])",
        (scopes,)).fetchall()}
    return {r["id"]: stored.get((r[scope], r[business]), r["id"]) for r in rows}


def publish(conn: psycopg.Connection, lote_id: str, corte: datetime, sesiones: list[str],
            tables: dict[str, list[dict]], before_commit: Callable[[psycopg.Connection], None] | None = None) -> None:
    """Replaces the facts of ``sesiones`` and moves their publication marker, all in one transaction."""
    with conn.transaction():
        with conn.cursor() as cur:
            for table in DIMENSIONS:
                _insert(cur, table, tables[table], UPSERT[table])
            canciones = _key_map(cur, "dim_cancion", "establecimiento_id", "cancion_ref", tables["dim_cancion"])
            participaciones = _key_map(cur, "dim_participacion", "sesion_id", "participante_ref",
                                       tables["dim_participacion"])

            for table in ("hecho_voto", "hecho_reproduccion", "hecho_solicitud"):
                cur.execute(f"DELETE FROM analitica.{table} WHERE sesion_id = ANY(%s::uuid[])", (sesiones,))
            for table in FACTS:
                rows = []
                for row in tables[table]:
                    row = {**row, "lote_id": lote_id, "cancion_id": canciones.get(row["cancion_id"], row["cancion_id"])}
                    if "participacion_id" in row:
                        row["participacion_id"] = participaciones.get(row["participacion_id"], row["participacion_id"])
                    rows.append(row)
                _insert(cur, table, rows)

            cur.executemany("""
                INSERT INTO analitica.publicacion_sesion (sesion_id, lote_id, version, publicada_at, corte_eventos_at)
                VALUES (%s, %s, 1, clock_timestamp(), %s)
                ON CONFLICT (sesion_id) DO UPDATE SET lote_id = EXCLUDED.lote_id,
                    version = analitica.publicacion_sesion.version + 1,
                    publicada_at = EXCLUDED.publicada_at, corte_eventos_at = EXCLUDED.corte_eventos_at""",
                            [(s, lote_id, corte) for s in sesiones])
            cur.execute("UPDATE integracion.lote_analitico SET estado = 'PUBLICADO', finalizado_at = clock_timestamp() "
                        "WHERE id = %s", (lote_id,))
            if before_commit is not None:
                before_commit(conn)


def plain_rows(rows) -> list[dict]:
    """Spark Rows -> dicts with database-ready values."""
    out = []
    for row in rows:
        d = row.asDict()
        out.append({k: (v if not isinstance(v, (datetime, date)) else _db_value(v)) for k, v in d.items()})
    return out
