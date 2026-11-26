"""Executable contracts (schema_version 1) of the outbox events the backend emits.

The catalogue of "Diccionario de eventos y procesamiento" names the event families; the field names below follow
what the backend actually writes (the executable contract), e.g. ``playback_id``/``origin``/``resultado`` for the
playback events. Every contract is closed: an undocumented property is rejected (additionalProperties=false).
"""

from __future__ import annotations

import json
import uuid
from datetime import date, datetime
from typing import Any, Callable

CONTRACT_VERSION = 1
MAX_PAYLOAD_BYTES = 64 * 1024
ALLOWED_SCOPES = ("B", "O")

SESSION_STATES = ("ABIERTA", "PAUSADA", "CERRANDO", "CERRADA")
ORIGINS = ("SOLICITUD", "DEFECTO")
PLAYBACK_RESULTS = ("COMPLETADA", "SALTADA", "FALLIDA", "INTERRUMPIDA_CIERRE")

Violation = tuple[str, str]


def _uuid(value: Any) -> bool:
    if not isinstance(value, str) or len(value) != 36:
        return False
    try:
        return str(uuid.UUID(value)) == value.lower()
    except ValueError:
        return False


def _int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool)


def _datetime(value: Any) -> bool:
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return value >= 0
    if not isinstance(value, str):
        return False
    try:
        datetime.fromisoformat(value.replace("Z", "+00:00"))
        return True
    except ValueError:
        return False


def _date(value: Any) -> bool:
    if not isinstance(value, str):
        return False
    try:
        date.fromisoformat(value)
        return len(value) == 10
    except ValueError:
        return False


def _str(value: Any) -> bool:
    return isinstance(value, str)


def _bool(value: Any) -> bool:
    return isinstance(value, bool)


def _str_array(value: Any) -> bool:
    return isinstance(value, list) and all(isinstance(item, str) for item in value)


class F:
    """One payload property: type check, presence, nullability and an optional domain check."""

    def __init__(self, check: Callable[[Any], bool], *, required: bool = True, nullable: bool = False,
                 domain: Callable[[Any], bool] | None = None):
        self.check = check
        self.required = required
        self.nullable = nullable
        self.domain = domain


def _min(limit: int) -> Callable[[Any], bool]:
    return lambda value: value >= limit


def _one_of(values: tuple[str, ...]) -> Callable[[Any], bool]:
    return lambda value: value in values


def _playback_origin(payload: dict) -> list[Violation]:
    origin, request, item = payload.get("origin"), payload.get("request_id"), payload.get("list_item_id")
    if origin == "SOLICITUD" and (request is None or item is not None):
        return [("ORIGEN_INCOHERENTE", "payload.request_id")]
    if origin == "DEFECTO" and (item is None or request is not None):
        return [("ORIGEN_INCOHERENTE", "payload.list_item_id")]
    return []


# event_type -> (scope requires a session, properties, extra cross-field rule)
CONTRACTS: dict[str, tuple[bool, dict[str, F], Callable[[dict], list[Violation]] | None]] = {
    "session.opened": (True, {
        "session_id": F(_uuid), "fecha_operativa": F(_date), "lista_version": F(_int, domain=_min(1)),
    }, None),
    "session.state_changed": (True, {
        "session_id": F(_uuid), "from_state": F(_str, domain=_one_of(SESSION_STATES)),
        "to_state": F(_str, domain=_one_of(SESSION_STATES)), "reason_code": F(_str),
    }, None),
    "session.closed": (True, {
        "closed_at": F(_datetime), "pendientes_al_cierre": F(_int, domain=_min(0)),
        "cierre_version": F(_int, domain=_min(0)),
    }, None),
    "request.created": (True, {
        "request_id": F(_uuid), "song_id": F(_uuid), "created_at": F(_datetime), "cycle": F(_int, domain=_min(1)),
    }, None),
    "vote.accepted": (True, {
        "vote_id": F(_uuid), "request_id": F(_uuid), "song_id": F(_uuid), "is_initial": F(_bool),
        "votes_after": F(_int, domain=_min(1)),
    }, None),
    "request.removed": (True, {
        "request_id": F(_uuid), "song_id": F(_uuid), "reason_code": F(_str),
    }, None),
    "playback.started": (True, {
        "playback_id": F(_uuid), "origin": F(_str, domain=_one_of(ORIGINS)),
        "request_id": F(_uuid, nullable=True), "list_item_id": F(_uuid, nullable=True), "song_id": F(_uuid),
        "votes_at_start": F(_int, domain=_min(0)),
    }, _playback_origin),
    "playback.finished": (True, {
        "playback_id": F(_uuid), "request_id": F(_uuid, nullable=True),
        "resultado": F(_str, domain=_one_of(PLAYBACK_RESULTS)), "reason_code": F(_str, required=False),
    }, None),
    "playback.source_changed": (True, {
        "from_source": F(_str, nullable=True, domain=_one_of(ORIGINS)), "to_source": F(_str, domain=_one_of(ORIGINS)),
        "reproduction_id": F(_uuid),
    }, None),
    "queue.changed": (True, {
        "queue_version": F(_int, domain=_min(0)), "reason_code": F(_str), "queue_size": F(_int, domain=_min(0)),
    }, None),
    "default_list.versioned": (False, {
        "list_id": F(_uuid), "version": F(_int, domain=_min(1)), "item_count": F(_int, domain=_min(0)),
        "rotation_policy": F(_str),
    }, None),
    "moderation.changed": (False, {
        "action": F(_str), "block_id": F(_uuid), "song_id": F(_uuid), "until_at": F(_datetime, nullable=True),
        "reason_code": F(_str, nullable=True),
    }, None),
    "configuration.changed": (False, {
        "entity_type": F(_str), "entity_ref": F(_uuid), "version": F(_int, domain=_min(0)),
        "field_codes": F(_str_array),
    }, None),
    "maintenance.executed": (False, {
        "session_ref": F(_uuid, nullable=True), "projection_reset_count": F(_int, domain=_min(0)), "result": F(_str),
    }, None),
}

ENVELOPE_REQUIRED = ("occurred_at", "agregado_tipo", "agregado_id", "agregado_version", "correlacion_id",
                     "productor")


def parse_payload(text: Any) -> dict | None:
    if not isinstance(text, str):
        return None
    try:
        value = json.loads(text)
    except ValueError:
        return None
    return value if isinstance(value, dict) else None


def validate_event(record: dict) -> list[Violation]:
    """Contract violations of one outbox record (empty when valid). Never copies payload values."""
    if not record.get("event_id"):
        return [("CAMPO_OBLIGATORIO", "event_id")]
    if record.get("ambito") not in ALLOWED_SCOPES:
        # Restricted (R) records must never be interpreted by general analytics.
        return [("AMBITO_RESTRINGIDO", "ambito")]
    contract = CONTRACTS.get(record.get("event_type"))
    if contract is None:
        return [("TIPO_DESCONOCIDO", "event_type")]
    if record.get("schema_version") != CONTRACT_VERSION:
        return [("VERSION_NO_SOPORTADA", "schema_version")]

    violations: list[Violation] = [("CAMPO_OBLIGATORIO", name) for name in ENVELOPE_REQUIRED
                                   if record.get(name) is None]
    needs_session, properties, extra = contract
    if needs_session and not record.get("sesion_id"):
        violations.append(("CAMPO_OBLIGATORIO", "sesion_id"))

    text = record.get("payload_json")
    if isinstance(text, str) and len(text.encode("utf-8")) > MAX_PAYLOAD_BYTES:
        return violations + [("PAYLOAD_TAMANO", "payload")]
    payload = parse_payload(text)
    if payload is None:
        return violations + [("PAYLOAD_INVALIDO", "payload")]

    for name in sorted(set(payload) - set(properties)):
        violations.append(("PROPIEDAD_NO_AUTORIZADA", f"payload.{name}"))
    for name, field in properties.items():
        path = f"payload.{name}"
        if name not in payload:
            if field.required:
                violations.append(("CAMPO_OBLIGATORIO", path))
            continue
        value = payload[name]
        if value is None:
            if not field.nullable:
                violations.append(("CAMPO_OBLIGATORIO", path))
            continue
        if not field.check(value):
            violations.append(("TIPO_INVALIDO", path))
        elif field.domain is not None and not field.domain(value):
            violations.append(("DOMINIO_INVALIDO", path))
    if extra is not None and not violations:
        violations.extend(extra(payload))
    if record.get("event_type") == "session.opened" and not violations \
            and payload["session_id"] != record.get("sesion_id"):
        violations.append(("REFERENCIA_INCOHERENTE", "payload.session_id"))
    return violations


BUSINESS_ID = {
    "request.created": "request_id",
    "vote.accepted": "vote_id",
    "playback.started": "playback_id",
    "playback.finished": "playback_id",
    "session.opened": "session_id",
}


def business_key(record: dict) -> str:
    """Identity of the fact (not of the delivery): two events with the same key are the same fact."""
    event_type = record["event_type"]
    field = BUSINESS_ID.get(event_type)
    if field is not None:
        payload = parse_payload(record.get("payload_json")) or {}
        return f"{event_type}:{payload.get(field)}"
    if event_type == "session.closed":
        return f"{event_type}:{record.get('sesion_id')}"
    return f"{event_type}:{record.get('agregado_tipo')}:{record.get('agregado_id')}:{record.get('agregado_version')}"


# Facts an event refers to: event_type -> (snapshot table, id property, coherence properties -> snapshot columns)
REFERENCES = {
    "request.created": ("solicitud", "request_id", {"song_id": "cancion_id"}),
    "vote.accepted": ("voto", "vote_id", {"request_id": "solicitud_id", "song_id": "cancion_id"}),
    "playback.started": ("reproduccion", "playback_id", {"song_id": "cancion_id"}),
    "playback.finished": ("reproduccion", "playback_id", {"request_id": "solicitud_id"}),
}
