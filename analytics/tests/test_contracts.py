import json

import pytest

from yousong_analytics.contracts import business_key, validate_event

SESION = "11111111-1111-1111-1111-111111111111"
U1 = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
U2 = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
U3 = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"


def event(event_type, payload, **overrides):
    record = {
        "event_id": "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
        "event_type": event_type,
        "schema_version": 1,
        "occurred_at": "2026-11-21T01:00:00Z",
        "establecimiento_id": "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
        "sesion_id": SESION,
        "agregado_tipo": "VOTO",
        "agregado_id": U1,
        "agregado_version": 0,
        "correlacion_id": U2,
        "productor": "backend.solicitudes",
        "ambito": "B",
        "payload_json": payload if isinstance(payload, str) else json.dumps(payload),
    }
    record.update(overrides)
    return record


VALID = {
    "session.opened": {"session_id": SESION, "fecha_operativa": "2026-11-20", "lista_version": 1},
    "session.state_changed": {"session_id": SESION, "from_state": "ABIERTA", "to_state": "PAUSADA",
                              "reason_code": "MANUAL"},
    "session.closed": {"closed_at": "2026-11-21T06:00:00.123456Z", "pendientes_al_cierre": 0, "cierre_version": 3},
    "request.created": {"request_id": U1, "song_id": U2, "created_at": "2026-11-21T01:00:00Z", "cycle": 1},
    "vote.accepted": {"vote_id": U1, "request_id": U2, "song_id": U3, "is_initial": True, "votes_after": 1},
    "request.removed": {"request_id": U1, "song_id": U2, "reason_code": "ELIMINADA_POR_PERSONAL"},
    "playback.started": {"playback_id": U1, "origin": "SOLICITUD", "request_id": U2, "list_item_id": None,
                         "song_id": U3, "votes_at_start": 2},
    "playback.finished": {"playback_id": U1, "request_id": None, "resultado": "COMPLETADA"},
    "playback.source_changed": {"from_source": None, "to_source": "DEFECTO", "reproduction_id": U1},
    "default_list.versioned": {"list_id": U1, "version": 2, "item_count": 10, "rotation_policy": "SECUENCIAL"},
    "queue.changed": {"queue_version": 4, "reason_code": "vote.accepted", "queue_size": 3},
    "moderation.changed": {"action": "BLOCKED", "block_id": U1, "song_id": U2, "until_at": None,
                           "reason_code": None},
    "configuration.changed": {"entity_type": "ESTABLECIMIENTO", "entity_ref": U1, "version": 1,
                              "field_codes": ["nombre"]},
    "maintenance.executed": {"session_ref": None, "projection_reset_count": 0, "result": "OK"},
}


@pytest.mark.parametrize("event_type", sorted(VALID))
def test_backend_payloads_pass_their_contract(event_type):
    sesion = None if event_type in ("default_list.versioned", "configuration.changed") else SESION
    assert validate_event(event(event_type, VALID[event_type], sesion_id=sesion)) == []


def test_epoch_number_is_accepted_as_date_time():
    payload = {**VALID["session.closed"], "closed_at": 1763704800.123456}
    assert validate_event(event("session.closed", payload)) == []


def test_missing_payload_field_is_reported_with_its_path():
    payload = dict(VALID["vote.accepted"])
    del payload["vote_id"]
    assert ("CAMPO_OBLIGATORIO", "payload.vote_id") in validate_event(event("vote.accepted", payload))


def test_unknown_property_is_rejected():
    payload = {**VALID["vote.accepted"], "nickname": "Juan"}
    assert validate_event(event("vote.accepted", payload)) == [("PROPIEDAD_NO_AUTORIZADA", "payload.nickname")]


def test_wrong_types_and_domains_are_rejected():
    payload = {**VALID["vote.accepted"], "vote_id": "not-a-uuid", "is_initial": "yes", "votes_after": 0}
    violations = validate_event(event("vote.accepted", payload))
    assert ("TIPO_INVALIDO", "payload.vote_id") in violations
    assert ("TIPO_INVALIDO", "payload.is_initial") in violations
    assert ("DOMINIO_INVALIDO", "payload.votes_after") in violations
    bad_result = {**VALID["playback.finished"], "resultado": "completed"}
    assert validate_event(event("playback.finished", bad_result)) == [("DOMINIO_INVALIDO", "payload.resultado")]


def test_playback_origin_must_match_its_reference():
    payload = {**VALID["playback.started"], "request_id": None}
    assert ("ORIGEN_INCOHERENTE", "payload.request_id") in validate_event(event("playback.started", payload))


def test_envelope_rules():
    assert validate_event(event("unknown.type", {})) == [("TIPO_DESCONOCIDO", "event_type")]
    assert validate_event(event("vote.accepted", VALID["vote.accepted"], schema_version=2)) == [
        ("VERSION_NO_SOPORTADA", "schema_version")]
    assert validate_event(event("vote.accepted", VALID["vote.accepted"], ambito="R")) == [
        ("AMBITO_RESTRINGIDO", "ambito")]
    assert validate_event(event("vote.accepted", VALID["vote.accepted"], sesion_id=None)) == [
        ("CAMPO_OBLIGATORIO", "sesion_id")]
    assert validate_event(event("vote.accepted", VALID["vote.accepted"], event_id=None)) == [
        ("CAMPO_OBLIGATORIO", "event_id")]
    assert validate_event(event("vote.accepted", "[1, 2]")) == [("PAYLOAD_INVALIDO", "payload")]
    assert validate_event(event("vote.accepted", "{not json")) == [("PAYLOAD_INVALIDO", "payload")]


def test_payload_over_64_kib_is_rejected():
    huge = {**VALID["queue.changed"], "reason_code": "x" * 70_000}
    assert validate_event(event("queue.changed", huge)) == [("PAYLOAD_TAMANO", "payload")]


def test_business_keys_identify_the_fact_not_the_delivery():
    a = event("vote.accepted", VALID["vote.accepted"])
    b = event("vote.accepted", VALID["vote.accepted"], event_id=U3, agregado_version=7)
    assert business_key(a) == business_key(b) == f"vote.accepted:{U1}"
    started = event("playback.started", VALID["playback.started"])
    finished = event("playback.finished", VALID["playback.finished"])
    assert business_key(started) != business_key(finished)
    queue = event("queue.changed", VALID["queue.changed"])
    assert business_key(queue) == f"queue.changed:VOTO:{U1}:0"
