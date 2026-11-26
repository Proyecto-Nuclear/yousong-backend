"""End to end against a throwaway PostgreSQL with the backend's Flyway migrations applied.

Run through scripts/test.sh, which starts the database and sets ANALYTICS_TEST_DB_URL/USER/PASSWORD and
ANALYTICS_TEST_MIGRATIONS. Skipped when those variables are absent.
"""

import json
import os
import re
from decimal import Decimal
from pathlib import Path

import psycopg
import pytest

from yousong_analytics.config import Config
from yousong_analytics.pipeline import run_once
from yousong_analytics.pseudonym import participante_ref

pytestmark = pytest.mark.e2e

DB_URL = os.environ.get("ANALYTICS_TEST_DB_URL")
if not DB_URL:
    pytest.skip("ANALYTICS_TEST_DB_URL is not set (run scripts/test.sh)", allow_module_level=True)

KEY = "test-only-hmac-key-0123456789abcdef-xyz"
EST = "e0000000-0000-4000-8000-0000000000e1"
LISTA, ITEM, ESTACION = ("10000000-0000-4000-8000-000000000001", "11000000-0000-4000-8000-000000000001",
                         "ee000000-0000-4000-8000-000000000001")
C1, C2, C3 = ("c1000000-0000-4000-8000-000000000001", "c2000000-0000-4000-8000-000000000002",
              "c3000000-0000-4000-8000-000000000003")
S1, S2 = "51000000-0000-4000-8000-000000000001", "52000000-0000-4000-8000-000000000002"
P1, P2, P3, P4 = (f"9{i}000000-0000-4000-8000-00000000000{i}" for i in range(1, 5))
D1, D2, D3 = (f"d{i}000000-0000-4000-8000-00000000000{i}" for i in range(1, 4))


def sid(prefix, n):
    return f"{prefix}{n}000000-0000-4000-8000-00000000000{n}"


A = {n: sid("a", n) for n in range(1, 8)}   # solicitudes
B = {n: sid("b", n) for n in range(1, 5)}   # votos
R = {n: sid("f", n) for n in range(1, 7)}   # reproducciones

SEED = f"""
INSERT INTO operacion.establecimiento (id, nombre, qr_slug, zona_horaria) VALUES ('{EST}', 'Local', 'local', 'America/Bogota');
INSERT INTO operacion.cancion (id, youtube_video_id, titulo, genero_propio, fuente_clasificacion) VALUES
 ('{C1}', 'vid1', 'Song one', 'Salsa', 'Revisión del local'), ('{C2}', 'vid2', 'Song two', NULL, NULL),
 ('{C3}', 'vid3', 'Song three', 'Rock', 'Revisión del local');
INSERT INTO operacion.lista_defecto (id, establecimiento_id, nombre, version) VALUES ('{LISTA}', '{EST}', 'Repertorio general', 1);
INSERT INTO operacion.item_lista (id, lista_id, cancion_id, posicion) VALUES ('{ITEM}', '{LISTA}', '{C3}', 1);
INSERT INTO operacion.sesion (id, establecimiento_id, lista_id, fecha_operativa, estado, abierta_at, cerrada_at) VALUES
 ('{S1}', '{EST}', '{LISTA}', '2025-11-20', 'CERRADA', '2025-11-21T00:30:00Z', '2025-11-21T06:00:00Z'),
 ('{S2}', '{EST}', '{LISTA}', '2025-11-21', 'ABIERTA', '2025-11-22T00:30:00Z', NULL);
INSERT INTO operacion.estacion (id, establecimiento_id, nombre) VALUES ('{ESTACION}', '{EST}', 'Barra');
INSERT INTO operacion.dispositivo (id, canal, expira_at) VALUES
 ('{D1}', 'WEB', '2030-01-01T00:00:00Z'), ('{D2}', 'ANDROID', '2030-01-01T00:00:00Z'), ('{D3}', 'WEB', '2030-01-01T00:00:00Z');
INSERT INTO operacion.participacion (id, sesion_id, dispositivo_id, seudonimo, ingreso_at) VALUES
 ('{P1}', '{S1}', '{D1}', 'p-1', '2025-11-21T00:40:00Z'), ('{P2}', '{S1}', '{D2}', 'p-2', '2025-11-21T00:41:00Z'),
 ('{P3}', '{S1}', '{D3}', 'p-3', '2025-11-21T00:42:00Z'), ('{P4}', '{S2}', NULL, 'p-4', '2025-11-22T00:40:00Z');
INSERT INTO operacion.consentimiento (id, participacion_id, politica_version, finalidad, accion, canal) VALUES
 ('0f000000-0000-4000-8000-000000000004', '{P4}', '1', 'F1', 'AUTORIZA', 'ANDROID'),
 ('0f000000-0000-4000-8000-000000000001', '{P1}', '1', 'F1', 'AUTORIZA', 'ANDROID');
INSERT INTO operacion.solicitud (id, sesion_id, cancion_id, creador_id, estado, creada_at, finalizada_at) VALUES
 ('{A[1]}', '{S1}', '{C1}', '{P1}', 'ATENDIDA', '2025-11-21T01:00:00Z', '2025-11-21T01:14:00Z'),
 ('{A[2]}', '{S1}', '{C2}', '{P2}', 'SALTADA', '2025-11-21T01:05:00Z', '2025-11-21T01:16:00Z'),
 ('{A[3]}', '{S1}', '{C3}', '{P1}', 'FALLIDA', '2025-11-21T04:59:00Z', '2025-11-21T05:00:30Z'),
 ('{A[4]}', '{S1}', '{C1}', '{P2}', 'NO_ATENDIDA_CIERRE', '2025-11-21T05:30:00Z', '2025-11-21T06:00:00Z'),
 ('{A[5]}', '{S1}', '{C2}', '{P1}', 'NO_ATENDIDA_CIERRE', '2025-11-21T05:40:00Z', '2025-11-21T06:00:00Z'),
 ('{A[6]}', '{S2}', '{C1}', '{P4}', 'REPRODUCIENDO', '2025-11-22T01:00:00Z', NULL),
 ('{A[7]}', '{S2}', '{C2}', '{P4}', 'PENDIENTE', '2025-11-22T01:10:00Z', NULL);
INSERT INTO operacion.voto (id, solicitud_id, participacion_id, sesion_id, cancion_id, es_inicial, creado_at) VALUES
 ('{B[1]}', '{A[1]}', '{P1}', '{S1}', '{C1}', true, '2025-11-21T01:00:00Z'),
 ('{B[2]}', '{A[1]}', '{P2}', '{S1}', '{C1}', false, '2025-11-21T01:02:00Z'),
 ('{B[3]}', '{A[2]}', '{P2}', '{S1}', '{C2}', true, '2025-11-21T01:05:00Z'),
 ('{B[4]}', '{A[6]}', '{P4}', '{S2}', '{C1}', true, '2025-11-22T01:00:00Z');
INSERT INTO operacion.reproduccion (id, sesion_id, cancion_id, estacion_id, origen, solicitud_id, item_lista_id,
                                    preparada_at, iniciada_at, terminada_at, resultado) VALUES
 ('{R[1]}', '{S1}', '{C1}', '{ESTACION}', 'SOLICITUD', '{A[1]}', NULL, '2025-11-21T01:10:00Z', '2025-11-21T01:10:02.5Z', '2025-11-21T01:14:00Z', 'COMPLETADA'),
 ('{R[2]}', '{S1}', '{C2}', '{ESTACION}', 'SOLICITUD', '{A[2]}', NULL, '2025-11-21T01:15:00Z', '2025-11-21T01:15:00Z', '2025-11-21T01:16:00Z', 'SALTADA'),
 ('{R[3]}', '{S1}', '{C3}', '{ESTACION}', 'SOLICITUD', '{A[3]}', NULL, '2025-11-21T05:00:00Z', NULL, '2025-11-21T05:00:30Z', 'FALLIDA'),
 ('{R[4]}', '{S1}', '{C1}', '{ESTACION}', 'SOLICITUD', '{A[4]}', NULL, '2025-11-21T05:55:00Z', '2025-11-21T05:55:01Z', '2025-11-21T06:00:00Z', 'INTERRUMPIDA_CIERRE'),
 ('{R[5]}', '{S1}', '{C3}', '{ESTACION}', 'DEFECTO', NULL, '{ITEM}', '2025-11-21T00:31:00Z', '2025-11-21T00:31:00Z', '2025-11-21T00:35:00Z', 'COMPLETADA'),
 ('{R[6]}', '{S2}', '{C1}', '{ESTACION}', 'SOLICITUD', '{A[6]}', NULL, '2025-11-22T01:20:00Z', '2025-11-22T01:20:00Z', NULL, NULL);
"""

def events():
    def e(n, event_type, occurred, payload, sesion=S1, agregado=None, version=0, ambito="B"):
        return (f"0e000000-0000-4000-8000-{n:012d}", event_type, occurred, EST, sesion, "X",
                agregado or f"0a000000-0000-4000-8000-{n:012d}", version, ambito, json.dumps(payload))

    vote = {"vote_id": B[1], "request_id": A[1], "song_id": C1, "is_initial": True, "votes_after": 1}
    return [
        e(1, "session.opened", "2025-11-21T00:30:00Z", {"session_id": S1, "fecha_operativa": "2025-11-20",
                                                        "lista_version": 1}),
        e(2, "request.created", "2025-11-21T01:00:00Z", {"request_id": A[1], "song_id": C1,
                                                         "created_at": "2025-11-21T01:00:00Z", "cycle": 1}),
        e(3, "vote.accepted", "2025-11-21T01:00:00Z", vote),
        e(4, "vote.accepted", "2025-11-21T01:02:00Z", {**vote, "vote_id": B[2], "is_initial": False,
                                                       "votes_after": 2}),
        e(5, "playback.started", "2025-11-21T01:10:02Z", {"playback_id": R[1], "origin": "SOLICITUD",
                                                          "request_id": A[1], "list_item_id": None, "song_id": C1,
                                                          "votes_at_start": 2}),
        e(6, "playback.finished", "2025-11-21T01:14:00Z", {"playback_id": R[1], "request_id": A[1],
                                                           "resultado": "COMPLETADA"}),
        e(7, "session.closed", "2025-11-21T06:00:00Z", {"closed_at": "2025-11-21T06:00:00Z",
                                                        "pendientes_al_cierre": 2, "cierre_version": 2}),
        # Same vote delivered again under a new event id.
        e(8, "vote.accepted", "2025-11-21T01:00:01Z", vote, agregado="0a000000-0000-4000-8000-000000000003",
          version=1),
        # Contract violation and dangling reference.
        e(9, "vote.accepted", "2025-11-21T01:03:00Z", {**vote, "nickname": "Ana"}),
        e(10, "vote.accepted", "2025-11-21T01:04:00Z", {**vote, "vote_id": "b9000000-0000-4000-8000-000000000009"}),
        # Restricted scope: must never be read.
        e(11, "privacy.consent_recorded", "2025-11-21T00:40:00Z",
          {"policy_version": "1", "purpose_codes": ["F1"], "action": "AUTORIZA"}, ambito="R"),
        e(12, "request.created", "2025-11-22T01:00:00Z", {"request_id": A[6], "song_id": C1,
                                                          "created_at": "2025-11-22T01:00:00Z", "cycle": 1},
          sesion=S2),
    ]


@pytest.fixture(scope="module")
def database():
    conn = psycopg.connect(DB_URL, user=os.environ["ANALYTICS_TEST_DB_USER"],
                           password=os.environ["ANALYTICS_TEST_DB_PASSWORD"], autocommit=True)
    conn.execute("DROP SCHEMA IF EXISTS analitica, integracion, operacion CASCADE")
    migrations = Path(os.environ.get("ANALYTICS_TEST_MIGRATIONS", "/migrations"))
    files = sorted(migrations.glob("V*__*.sql"), key=lambda p: int(re.match(r"V(\d+)__", p.name).group(1)))
    assert files, f"No Flyway migrations found in {migrations}"
    for path in files:
        with conn.transaction():
            conn.execute(path.read_text(encoding="utf-8"))
    conn.execute(SEED)
    with conn.cursor() as cur:
        cur.executemany("""
            INSERT INTO integracion.evento_outbox (event_id, event_type, occurred_at, establecimiento_id, sesion_id,
                agregado_tipo, agregado_id, agregado_version, correlacion_id, productor, ambito, payload)
            VALUES (%s, %s, %s, %s, %s, %s, %s, %s, gen_random_uuid(), 'backend.test', %s, %s::jsonb)""", events())
    yield conn
    conn.close()


@pytest.fixture(scope="module")
def config(tmp_path_factory):
    return Config.from_env({
        "ANALYTICS_DB_URL": DB_URL, "ANALYTICS_DB_USER": os.environ["ANALYTICS_TEST_DB_USER"],
        "ANALYTICS_DB_PASSWORD": os.environ["ANALYTICS_TEST_DB_PASSWORD"], "ANALYTICS_HMAC_KEY": KEY,
        "ANALYTICS_PARQUET_DIR": str(tmp_path_factory.mktemp("parquet")), "ANALYTICS_LAG_SECONDS": "0"})


def one(conn, sql, *params):
    return conn.execute(sql, params).fetchone()


def all_(conn, sql, *params):
    return conn.execute(sql, params).fetchall()


def publications(conn):
    return {str(s): v for s, v in all_(conn, "SELECT sesion_id, version FROM analitica.publicacion_sesion")}


def fact_snapshot(conn):
    return (all_(conn, "SELECT solicitud_ref, sesion_id, cancion_id, participacion_id, tiempo_id, estado, "
                       "primer_inicio_at, espera_real_s FROM analitica.hecho_solicitud ORDER BY 1"),
            all_(conn, "SELECT voto_ref, cancion_id, participacion_id, es_inicial FROM analitica.hecho_voto ORDER BY 1"),
            all_(conn, "SELECT reproduccion_ref, origen, solicitud_ref, lista_id, iniciada_at, resultado "
                       "FROM analitica.hecho_reproduccion ORDER BY 1"))


@pytest.fixture(scope="module")
def first_run(database, config, spark):
    return run_once(config, spark)


def test_first_run_publishes_a_reconciled_batch(database, first_run):
    assert first_run.estado == "PUBLICADO"
    lote = one(database, "SELECT estado, leidos, aceptados, duplicados, rechazados, pendientes, ruta_parquet, "
                         "finalizado_at IS NOT NULL FROM integracion.lote_analitico WHERE id = %s", first_run.lote_id)
    # 12 events in the outbox, the restricted one is never read.
    assert lote[:6] == ("PUBLICADO", 11, 8, 1, 2, 0)
    assert lote[6] == f"raw/fecha={lote[6][10:20]}/lote={first_run.lote_id}" and lote[7]
    assert publications(database) == {S1: 1, S2: 1}
    marker = one(database, "SELECT lote_id, corte_eventos_at > %s::timestamptz FROM analitica.publicacion_sesion "
                           "WHERE sesion_id = %s", "2025-11-21T06:00:00Z", S1)
    assert str(marker[0]) == first_run.lote_id and marker[1]


def test_invalid_records_are_quarantined_without_restricted_events(database, first_run):
    rows = all_(database, "SELECT event_id::text, regla_codigo, campo_ruta, registro_ref FROM "
                          "integracion.rechazo_ingesta WHERE lote_id = %s ORDER BY 1", first_run.lote_id)
    assert [(r[0][-2:], r[1], r[2]) for r in rows] == [("09", "PROPIEDAD_NO_AUTORIZADA", "payload.nickname"),
                                                       ("10", "REFERENCIA_INEXISTENTE", "payload.vote_id")]
    assert all(r[3].startswith(f"quarantine/lote={first_run.lote_id}/") for r in rows)
    assert one(database, "SELECT count(*) FROM integracion.rechazo_ingesta WHERE event_id = %s",
               "0e000000-0000-4000-8000-000000000011")[0] == 0


def test_dimensions_and_id_mapping(database, first_run):
    assert one(database, "SELECT nombre, zona_horaria FROM analitica.dim_establecimiento WHERE id = %s", EST) == (
        "Local", "America/Bogota")
    assert all_(database, "SELECT id::text, estado FROM analitica.dim_sesion ORDER BY abierta_at") == [
        (S1, "CERRADA"), (S2, "ABIERTA")]
    songs = {str(r[0]): (r[1], r[2]) for r in all_(
        database, "SELECT cancion_ref, genero_propio, id::text FROM analitica.dim_cancion")}
    assert {k: v[0] for k, v in songs.items()} == {C1: "Salsa", C2: None, C3: "Rock"}
    # cancion_ref is the operational song id: the reader resolves titles through it.
    assert one(database, "SELECT c.titulo FROM analitica.hecho_solicitud h JOIN analitica.dim_cancion d "
                         "ON d.id = h.cancion_id JOIN operacion.cancion c ON c.id = d.cancion_ref "
                         "WHERE h.solicitud_ref = %s", A[1])[0] == "Song one"
    assert one(database, "SELECT id::text, version FROM analitica.dim_lista") == (LISTA, 1)


def test_participation_is_pseudonymous(database, first_run):
    rows = all_(database, "SELECT sesion_id::text, participante_ref, canal FROM analitica.dim_participacion")
    assert len(rows) == 4
    expected = {participante_ref(KEY.encode(), S1, P1): "ANDROID", participante_ref(KEY.encode(), S2, P4): "ANDROID"}
    by_ref = {r[1]: r[2] for r in rows}
    assert all(by_ref[ref] == canal for ref, canal in expected.items())
    stored = {str(r[0]) for r in all_(database, "SELECT id FROM analitica.dim_participacion")}
    assert not stored & {P1, P2, P3, P4}


def test_request_and_playback_states(database, first_run):
    s = {str(r[0]): r for r in fact_snapshot(database)[0]}
    assert s[A[1]][5] == "ATENDIDA" and s[A[1]][7] == Decimal("602.500")
    assert s[A[3]][5] == "FALLIDA" and s[A[3]][6] is None and s[A[3]][7] is None
    assert s[A[4]][5] == "NO_ATENDIDA_CIERRE" and s[A[4]][7] == Decimal("1501.000")
    assert s[A[5]][6] is None and s[A[7]][5] == "PENDIENTE" and s[A[6]][5] == "REPRODUCIENDO"
    assert s[A[1]][4] == 2025112020
    r = {str(row[0]): row for row in fact_snapshot(database)[2]}
    assert {k[:2]: v[5] for k, v in r.items()} == {"f1": "COMPLETADA", "f2": "SALTADA", "f3": "FALLIDA",
                                                   "f4": "INTERRUMPIDA_CIERRE", "f5": "COMPLETADA", "f6": None}
    assert r[R[3]][4] is None
    assert r[R[5]][1] == "DEFECTO" and str(r[R[5]][3]) == LISTA and r[R[5]][2] is None
    votes = fact_snapshot(database)[1]
    assert len(votes) == 4 and sum(1 for v in votes if v[3]) == 3


def test_parquet_is_private_and_pseudonymous(config, first_run):
    data = b"".join(p.read_bytes() for p in Path(config.parquet_dir).rglob("*.parquet"))
    assert data
    assert KEY.encode() not in data
    for operational in (P1, P2, P3, P4, D1, D2, D3):
        assert operational.encode() not in data
    assert b"0e000000-0000-4000-8000-000000000011" not in data
    assert (Path(config.parquet_dir) / f"quarantine/lote={first_run.lote_id}").is_dir()


def test_rerun_is_idempotent_and_closed_sessions_are_not_republished(database, config, spark, first_run):
    before = fact_snapshot(database)
    second = run_once(config, spark)
    assert second.estado == "PUBLICADO"
    assert one(database, "SELECT leidos, aceptados FROM integracion.lote_analitico WHERE id = %s",
               second.lote_id) == (0, 0)
    assert publications(database) == {S1: 1, S2: 2}
    assert fact_snapshot(database) == before


def test_failed_publication_keeps_the_previous_one(database, config, spark, first_run):
    before_pub, before_facts = publications(database), fact_snapshot(database)

    def boom(conn):
        raise RuntimeError("injected failure before commit")

    failed = run_once(config, spark, before_commit=boom)
    assert failed.estado == "FALLIDO"
    lote = one(database, "SELECT estado, leidos = aceptados + duplicados + rechazados + pendientes, pendientes "
                         "FROM integracion.lote_analitico WHERE id = %s", failed.lote_id)
    assert lote == ("FALLIDO", True, 0)
    assert publications(database) == before_pub
    assert fact_snapshot(database) == before_facts


def test_unreconciled_session_is_not_published(database, config, spark, first_run):
    before = publications(database)
    # An attended request whose latest attempt was skipped: the states contradict each other.
    database.execute("UPDATE operacion.solicitud SET estado = 'ATENDIDA', finalizada_at = now() WHERE id = %s", (A[2],))
    run = run_once(config, spark)
    assert run.estado == "PUBLICADO" and run.no_conciliadas == [S1]
    assert publications(database) == {S1: before[S1], S2: before[S2] + 1}
    assert one(database, "SELECT estado FROM analitica.hecho_solicitud WHERE solicitud_ref = %s", A[2])[0] == "SALTADA"
    rules = {r[0] for r in all_(database, "SELECT regla_codigo FROM integracion.rechazo_ingesta WHERE lote_id = %s "
                                          "AND event_id IS NULL", run.lote_id)}
    assert rules == {"CONCILIACION_ATENDIDA", "CONCILIACION_RESULTADO"}
