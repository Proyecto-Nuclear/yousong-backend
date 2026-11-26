import json

from tests.conftest import S1, S2, snapshot_rows, utc
from yousong_analytics.schemas import event_dataframe, snapshot_from_rows
from yousong_analytics.validate import classify

VOTE_B1 = {"vote_id": "b1000000-0000-4000-8000-000000000001", "request_id": "a1000000-0000-4000-8000-000000000001",
           "song_id": "c1000000-0000-4000-8000-000000000001", "is_initial": True, "votes_after": 1}


def ev(event_id, event_type, payload, sesion=S1, ambito="B", occurred="2026-11-21T01:00:00Z", **extra):
    row = {"event_id": event_id, "event_type": event_type, "schema_version": 1, "occurred_at": utc(occurred),
           "establecimiento_id": "e0000000-0000-4000-8000-000000000001", "sesion_id": sesion,
           "agregado_tipo": "VOTO", "agregado_id": event_id, "agregado_version": 0,
           "correlacion_id": "0c000000-0000-4000-8000-000000000001", "causacion_id": None,
           "productor": "backend.solicitudes", "ambito": ambito, "payload_json": json.dumps(payload)}
    row.update(extra)
    return row


def test_classification_counts_every_record_exactly_once(spark):
    events = [
        ev("00000000-0000-4000-8000-000000000001", "vote.accepted", VOTE_B1),
        # Same fact delivered again under another event id: duplicate.
        ev("00000000-0000-4000-8000-000000000002", "vote.accepted", VOTE_B1, occurred="2026-11-21T01:00:01Z"),
        # Same delivery read twice: duplicate by event id.
        ev("00000000-0000-4000-8000-000000000001", "vote.accepted", VOTE_B1),
        # Contract violation.
        ev("00000000-0000-4000-8000-000000000003", "vote.accepted", {**VOTE_B1, "nickname": "Ana"}),
        # Refers to a vote that does not exist in the session.
        ev("00000000-0000-4000-8000-000000000004", "vote.accepted",
           {**VOTE_B1, "vote_id": "b9000000-0000-4000-8000-000000000009"}),
        # Vote of S1 declared on S2.
        ev("00000000-0000-4000-8000-000000000005", "vote.accepted", VOTE_B1, sesion=S2),
        ev("00000000-0000-4000-8000-000000000006", "queue.changed",
           {"queue_version": 1, "reason_code": "vote.accepted", "queue_size": 1}),
        ev("00000000-0000-4000-8000-000000000007", "playback.finished",
           {"playback_id": "f3000000-0000-4000-8000-000000000003", "request_id": "a3000000-0000-4000-8000-000000000003",
            "resultado": "FALLIDA", "reason_code": "VIDEO_NO_DISPONIBLE"}),
    ]
    snapshot = snapshot_from_rows(spark, snapshot_rows())
    result = classify(spark, event_dataframe(spark, events), snapshot)

    assert result.counters == {"leidos": 8, "aceptados": 3, "duplicados": 2, "rechazados": 3}
    assert sorted(r["event_id"] for r in result.accepted.collect()) == [
        "00000000-0000-4000-8000-000000000001", "00000000-0000-4000-8000-000000000006",
        "00000000-0000-4000-8000-000000000007"]
    rules = sorted((r["event_id"][-1], r["regla_codigo"], r["campo_ruta"]) for r in result.rejections)
    assert rules == [("3", "PROPIEDAD_NO_AUTORIZADA", "payload.nickname"),
                     ("4", "REFERENCIA_INEXISTENTE", "payload.vote_id"),
                     ("5", "REFERENCIA_INEXISTENTE", "payload.vote_id")]


def test_one_record_breaking_several_rules_counts_once(spark):
    bad = {**VOTE_B1, "vote_id": "nope", "votes_after": 0}
    events = [ev("00000000-0000-4000-8000-000000000001", "vote.accepted", bad)]
    result = classify(spark, event_dataframe(spark, events), snapshot_from_rows(spark, snapshot_rows()))
    assert result.counters == {"leidos": 1, "aceptados": 0, "duplicados": 0, "rechazados": 1}
    assert {r["regla_codigo"] for r in result.rejections} == {"TIPO_INVALIDO", "DOMINIO_INVALIDO"}


def test_restricted_scope_never_reaches_accepted(spark):
    events = [ev("00000000-0000-4000-8000-000000000001", "privacy.consent_recorded",
                 {"policy_version": "1", "purpose_codes": ["F1"], "action": "AUTORIZA"}, ambito="R")]
    result = classify(spark, event_dataframe(spark, events), snapshot_from_rows(spark, snapshot_rows()))
    assert result.accepted.count() == 0
    assert [(r["regla_codigo"]) for r in result.rejections] == ["AMBITO_RESTRINGIDO"]


def test_empty_input(spark):
    result = classify(spark, event_dataframe(spark, []), snapshot_from_rows(spark, snapshot_rows()))
    assert result.counters == {"leidos": 0, "aceptados": 0, "duplicados": 0, "rechazados": 0}
    assert result.rejections == []
