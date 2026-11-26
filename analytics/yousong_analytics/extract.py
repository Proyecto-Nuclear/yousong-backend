"""Ingestion: one consistent read of the outbox range and of the operational state of the affected sessions.

Everything is read in a single REPEATABLE READ READ ONLY transaction, so events and operational rows describe the
same instant. Restricted (R) events are excluded by the query itself and never leave the database. Operational
participation and device ids are replaced by their session pseudonym before anything leaves this module.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from uuid import UUID

import psycopg
from psycopg.rows import dict_row

from .pseudonym import participante_ref


@dataclass
class Extraction:
    rango_desde: datetime
    rango_hasta: datetime
    events: list[dict]
    sesiones: list[str]
    snapshot: dict[str, list[dict]]


WATERMARK = """
SELECT max(rango_hasta) AS hasta FROM integracion.lote_analitico
 WHERE estado = 'PUBLICADO' OR (estado = 'VALIDADO' AND finalizado_at IS NOT NULL)
"""

EVENTS = """
SELECT event_id, event_type, schema_version, occurred_at, establecimiento_id, sesion_id, agregado_tipo,
       agregado_id, agregado_version, correlacion_id, causacion_id, productor, ambito, payload::text AS payload_json
  FROM integracion.evento_outbox
 WHERE ambito IN ('B', 'O') AND occurred_at >= %(desde)s AND occurred_at < %(hasta)s
 ORDER BY occurred_at, event_id
"""

# Sessions to (re)publish: touched by an event of the range, still in operation, never published, closed after
# their last publication cut, or with requests/attempts finalized after it (e.g. maintenance after close).
SESSIONS = """
SELECT s.id
  FROM operacion.sesion s
  LEFT JOIN analitica.publicacion_sesion p ON p.sesion_id = s.id
 WHERE s.abierta_at < %(hasta)s
   AND (s.id = ANY(%(event_sessions)s::uuid[])
        OR s.estado IN ('ABIERTA', 'PAUSADA', 'CERRANDO')
        OR p.sesion_id IS NULL
        OR (s.estado = 'CERRADA' AND p.corte_eventos_at < s.cerrada_at)
        OR EXISTS (SELECT 1 FROM operacion.solicitud q
                    WHERE q.sesion_id = s.id AND q.finalizada_at >= p.corte_eventos_at)
        OR EXISTS (SELECT 1 FROM operacion.reproduccion x
                    WHERE x.sesion_id = s.id AND x.terminada_at >= p.corte_eventos_at))
 ORDER BY s.abierta_at, s.id
"""

SNAPSHOT = {
    "establecimiento": """
        SELECT DISTINCT e.id, e.nombre, e.zona_horaria FROM operacion.establecimiento e
          JOIN operacion.sesion s ON s.establecimiento_id = e.id WHERE s.id = ANY(%(ids)s::uuid[])""",
    "sesion": """
        SELECT id, establecimiento_id, lista_id, fecha_operativa, estado, abierta_at, cerrada_at
          FROM operacion.sesion WHERE id = ANY(%(ids)s::uuid[])""",
    "lista": """
        SELECT l.id, l.establecimiento_id, l.nombre, l.version FROM operacion.lista_defecto l
         WHERE l.id IN (SELECT lista_id FROM operacion.sesion WHERE id = ANY(%(ids)s::uuid[])
                        UNION SELECT il.lista_id FROM operacion.reproduccion r
                                JOIN operacion.item_lista il ON il.id = r.item_lista_id
                               WHERE r.sesion_id = ANY(%(ids)s::uuid[]))""",
    # Channel declared when joining (first consent), as the live endpoint reads it: a known phone keeps its original
    # channel in `dispositivo` even if it joins from the other client. The device is the fallback.
    "participacion": """
        SELECT p.id, p.sesion_id, p.ingreso_at,
               coalesce((SELECT c.canal FROM operacion.consentimiento c WHERE c.participacion_id = p.id
                          ORDER BY c.registrado_at, c.id LIMIT 1), d.canal) AS canal
          FROM operacion.participacion p LEFT JOIN operacion.dispositivo d ON d.id = p.dispositivo_id
         WHERE p.sesion_id = ANY(%(ids)s::uuid[])""",
    "cancion": """
        SELECT c.id, c.genero_propio, c.fuente_clasificacion FROM operacion.cancion c
         WHERE c.id IN (SELECT cancion_id FROM operacion.solicitud WHERE sesion_id = ANY(%(ids)s::uuid[])
                        UNION SELECT cancion_id FROM operacion.voto WHERE sesion_id = ANY(%(ids)s::uuid[])
                        UNION SELECT cancion_id FROM operacion.reproduccion WHERE sesion_id = ANY(%(ids)s::uuid[]))""",
    "solicitud": """
        SELECT id, sesion_id, cancion_id, creador_id, estado, creada_at
          FROM operacion.solicitud WHERE sesion_id = ANY(%(ids)s::uuid[])""",
    "voto": """
        SELECT id, solicitud_id, sesion_id, cancion_id, participacion_id, es_inicial, creado_at
          FROM operacion.voto WHERE sesion_id = ANY(%(ids)s::uuid[])""",
    "reproduccion": """
        SELECT r.id, r.sesion_id, r.cancion_id, r.origen, r.solicitud_id, il.lista_id, r.preparada_at,
               r.iniciada_at, r.terminada_at, r.resultado
          FROM operacion.reproduccion r LEFT JOIN operacion.item_lista il ON il.id = r.item_lista_id
         WHERE r.sesion_id = ANY(%(ids)s::uuid[])""",
}


def _plain(row: dict) -> dict:
    return {k: (str(v) if isinstance(v, UUID) else v) for k, v in row.items()}


def pseudonymize(snapshot: dict[str, list[dict]], key: bytes) -> dict[str, list[dict]]:
    """Replaces operational participation ids by participante_ref (HMAC per session). Returns new lists."""
    out = dict(snapshot)
    out["participacion"] = [{"sesion_id": p["sesion_id"], "participante_ref": participante_ref(key, p["sesion_id"], p["id"]),
                             "canal": p["canal"], "ingreso_at": p["ingreso_at"]} for p in snapshot["participacion"]]
    out["solicitud"] = [{**{k: v for k, v in s.items() if k != "creador_id"},
                         "creador_ref": participante_ref(key, s["sesion_id"], s["creador_id"])}
                        for s in snapshot["solicitud"]]
    out["voto"] = [{**{k: v for k, v in v_.items() if k != "participacion_id"},
                    "participante_ref": participante_ref(key, v_["sesion_id"], v_["participacion_id"])}
                   for v_ in snapshot["voto"]]
    return out


def read_batch(conn: psycopg.Connection, key: bytes, lag_seconds: int) -> Extraction:
    with conn.transaction():
        conn.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY")
        with conn.cursor(row_factory=dict_row) as cur:
            hasta = cur.execute("SELECT now() - make_interval(secs => %s) AS hasta", (lag_seconds,)).fetchone()["hasta"]
            desde = cur.execute(WATERMARK).fetchone()["hasta"]
            if desde is None:
                desde = cur.execute("SELECT min(occurred_at) AS m FROM integracion.evento_outbox "
                                    "WHERE ambito IN ('B', 'O')").fetchone()["m"]
            desde = hasta if desde is None or desde > hasta else desde
            events = [_plain(r) for r in cur.execute(EVENTS, {"desde": desde, "hasta": hasta}).fetchall()]
            event_sessions = sorted({e["sesion_id"] for e in events if e["sesion_id"]})
            ids = [str(r["id"]) for r in cur.execute(SESSIONS, {"hasta": hasta,
                                                                  "event_sessions": event_sessions}).fetchall()]
            snapshot = {name: [_plain(r) for r in cur.execute(sql, {"ids": ids}).fetchall()]
                        for name, sql in SNAPSHOT.items()}
    return Extraction(rango_desde=desde, rango_hasta=hasta, events=events, sesiones=ids,
                      snapshot=pseudonymize(snapshot, key))
