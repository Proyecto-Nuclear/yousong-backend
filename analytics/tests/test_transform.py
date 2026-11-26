from datetime import date
from decimal import Decimal

from tests.conftest import C1, C2, EST, LISTA, REF_A, S1, S2, rep, snapshot_rows, sol, utc
from yousong_analytics.ids import dim_cancion_id, dim_participacion_id
from yousong_analytics.schemas import snapshot_from_rows
from yousong_analytics.transform import build_model, reconcile


def rows(df, key):
    return {r[key]: r.asDict() for r in df.collect()}


def test_dimensions_use_operational_ids_and_stable_analytic_keys(spark):
    model = build_model(snapshot_from_rows(spark, snapshot_rows()))
    assert rows(model["dim_establecimiento"], "id")[EST]["zona_horaria"] == "America/Bogota"
    sesiones = rows(model["dim_sesion"], "id")
    assert sesiones[S1]["fecha_operativa"] == date(2026, 11, 20) and sesiones[S1]["estado"] == "CERRADA"
    assert rows(model["dim_lista"], "id")[LISTA]["version"] == 1

    canciones = rows(model["dim_cancion"], "cancion_ref")
    assert canciones[C1]["id"] == dim_cancion_id(EST, C1)
    assert canciones[C1]["genero_propio"] == "Salsa"
    assert canciones[C2]["genero_propio"] is None

    participaciones = rows(model["dim_participacion"], "participante_ref")
    assert len(participaciones) == 4
    assert participaciones[REF_A]["id"] == dim_participacion_id(S1, REF_A)


def test_time_dimension_is_local_and_crosses_midnight(spark):
    model = build_model(snapshot_from_rows(spark, snapshot_rows()))
    solicitudes = rows(model["hecho_solicitud"], "solicitud_ref")
    # 01:00Z on 2026-11-21 is 20:00 on Friday 2026-11-20 in Bogota (UTC-5).
    assert solicitudes["a1000000-0000-4000-8000-000000000001"]["tiempo_id"] == 2026112020
    # 05:30Z is 00:30 on Saturday 2026-11-21: a new calendar day of the same night.
    assert solicitudes["a4000000-0000-4000-8000-000000000004"]["tiempo_id"] == 2026112100
    tiempo = rows(model["dim_tiempo"], "id")
    assert tiempo[2026112020] == {"id": 2026112020, "fecha_local": date(2026, 11, 20), "hora": 20, "dia_semana": 5}
    assert tiempo[2026112100]["dia_semana"] == 6


def test_request_states_start_and_real_wait(spark):
    model = build_model(snapshot_from_rows(spark, snapshot_rows()))
    s = rows(model["hecho_solicitud"], "solicitud_ref")
    attended = s["a1000000-0000-4000-8000-000000000001"]
    assert attended["estado"] == "ATENDIDA"
    assert attended["primer_inicio_at"].replace(tzinfo=None) == utc("2026-11-21T01:10:02.500Z").replace(tzinfo=None)
    assert attended["espera_real_s"] == Decimal("602.500")
    assert attended["participacion_id"] == dim_participacion_id(S1, REF_A)
    assert attended["cancion_id"] == dim_cancion_id(EST, C1)
    # Failed before PLAYING: no start, no wait.
    failed = s["a3000000-0000-4000-8000-000000000003"]
    assert failed["estado"] == "FALLIDA" and failed["primer_inicio_at"] is None and failed["espera_real_s"] is None
    # Interrupted by close after starting: started, not completed.
    interrupted = s["a4000000-0000-4000-8000-000000000004"]
    assert interrupted["estado"] == "NO_ATENDIDA_CIERRE" and interrupted["espera_real_s"] == Decimal("1501.000")
    never = s["a5000000-0000-4000-8000-000000000005"]
    assert never["estado"] == "NO_ATENDIDA_CIERRE" and never["espera_real_s"] is None
    assert s["a7000000-0000-4000-8000-000000000007"]["estado"] == "PENDIENTE"
    assert s["a6000000-0000-4000-8000-000000000006"]["estado"] == "REPRODUCIENDO"


def test_playback_results_and_origins(spark):
    model = build_model(snapshot_from_rows(spark, snapshot_rows()))
    r = rows(model["hecho_reproduccion"], "reproduccion_ref")
    assert {k[:2]: v["resultado"] for k, v in r.items()} == {
        "f1": "COMPLETADA", "f2": "SALTADA", "f3": "FALLIDA", "f4": "INTERRUMPIDA_CIERRE", "f5": "COMPLETADA",
        "f6": None}
    assert r["f3000000-0000-4000-8000-000000000003"]["iniciada_at"] is None
    default = r["f5000000-0000-4000-8000-000000000005"]
    assert default["origen"] == "DEFECTO" and default["lista_id"] == LISTA and default["solicitud_ref"] is None
    requested = r["f1000000-0000-4000-8000-000000000001"]
    assert requested["solicitud_ref"] == "a1000000-0000-4000-8000-000000000001" and requested["lista_id"] is None


def test_votes_keep_initial_flag(spark):
    model = build_model(snapshot_from_rows(spark, snapshot_rows()))
    v = rows(model["hecho_voto"], "voto_ref")
    assert len(v) == 4
    assert v["b1000000-0000-4000-8000-000000000001"]["es_inicial"] is True
    assert v["b2000000-0000-4000-8000-000000000002"]["es_inicial"] is False


def test_consistent_snapshot_reconciles(spark):
    snapshot = snapshot_from_rows(spark, snapshot_rows())
    assert reconcile(build_model(snapshot)) == []


def test_attended_request_without_completed_playback_fails_its_session(spark):
    data = snapshot_rows()
    data["solicitud"][1] = sol("a2000000-0000-4000-8000-000000000002", S1, C2, "b" * 64, "ATENDIDA",
                               "2026-11-21T01:05:00Z")
    failures = reconcile(build_model(snapshot_from_rows(spark, data)))
    assert {(f.sesion_id, f.regla_codigo) for f in failures} == {
        (S1, "CONCILIACION_ATENDIDA"), (S1, "CONCILIACION_RESULTADO")}


def test_open_playback_in_closed_session_fails(spark):
    data = snapshot_rows()
    data["reproduccion"][3] = rep("f4000000-0000-4000-8000-000000000004", S1, C1, "SOLICITUD",
                                  "a4000000-0000-4000-8000-000000000004", None, "2026-11-21T05:55:00Z",
                                  "2026-11-21T05:55:01Z", None, None)
    failures = reconcile(build_model(snapshot_from_rows(spark, data)))
    assert (S1, "CONCILIACION_INTENTO_ABIERTO") in {(f.sesion_id, f.regla_codigo) for f in failures}
    assert all(f.sesion_id != S2 for f in failures)


def test_playing_request_needs_an_open_attempt(spark):
    data = snapshot_rows()
    data["reproduccion"] = [r for r in data["reproduccion"] if not r["id"].startswith("f6")]
    failures = reconcile(build_model(snapshot_from_rows(spark, data)))
    assert {(f.sesion_id, f.regla_codigo) for f in failures} == {(S2, "CONCILIACION_REPRODUCIENDO")}
