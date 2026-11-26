"""DEMO DATA for the presentation: a demo owner, the venue "La Sala Demo" and its history.

Run by `./deploy.sh demo` (or `deploy.ps1 demo`) inside the analytics image (Python 3.12 + psycopg). Everything it
creates is demo data:

* FusionAuth user DEMO_OWNER_EMAIL (password DEMO_OWNER_PASSWORD from config.env), registered to the app, no roles.
* Through the backend API, as that owner: the venue "La Sala Demo" (slug la-sala-demo) and its default list. Songs are
  real: YouTube's Colombian music chart (/api/public/tendencias) plus a few owner catalog searches, so titles and genres
  are whatever YouTube reports (never invented). Without YOUTUBE_API_KEY it stops with a message.
* Directly in PostgreSQL, in ONE transaction: 8 closed Friday/Saturday nights (20:00-02:00 America/Bogota) of the last
  four weekends, each with ~80 participants (WEB/ANDROID consents), ~120 requests in every terminal state, votes,
  request and default-list playbacks with every result, and one night open now (requests PENDIENTE, RESERVADA and
  REPRODUCIENDO). The rows follow the analytics reconciliation rules (analytics/README.md).

Idempotent: ids are deterministic (UUIDv5) and a venue that already has its demo nights is left untouched.
"""

from __future__ import annotations

import json
import os
import random
import sys
import time
import urllib.error
import urllib.request
import uuid
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta, timezone

import psycopg

BACKEND = os.environ.get("BACKEND_URL", "http://backend:8080")
FUSIONAUTH = os.environ.get("FUSIONAUTH_URL", "http://fusionauth:9011")
API_KEY = os.environ["FUSIONAUTH_API_KEY"]
APP_ID = os.environ["FUSIONAUTH_APPLICATION_ID"]
OWNER_EMAIL = os.environ.get("DEMO_OWNER_EMAIL", "demo@yousong.local")
OWNER_PASSWORD = os.environ["DEMO_OWNER_PASSWORD"]
OWNER_ID = "00000000-0000-4000-8000-0000000000d1"
VENUE_NAME = "La Sala Demo"
VENUE_SLUG = "la-sala-demo"
SEARCHES = ["salsa", "vallenato", "merengue", "bachata", "rock en español"]
POLICY_VERSION = "1.1"
NS = uuid.UUID("5d0c6a8e-2f0b-4d47-9a51-3c7e9b1f2a64")
BOGOTA = timezone(timedelta(hours=-5))  # Colombia has no daylight saving time
NIGHTS = 8
DEVICE_POOL = 140


def say(message: str) -> None:
    print(f"[demo] {message}", flush=True)


def fail(message: str) -> None:
    print(f"[demo] ERROR: {message}", file=sys.stderr, flush=True)
    sys.exit(1)


def http(method: str, url: str, body: dict | list | None = None, headers: dict | None = None) -> tuple[int, object]:
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method,
                                     headers={"Accept": "application/json",
                                              **({"Content-Type": "application/json"} if data else {}),
                                              **(headers or {})})
    try:
        with urllib.request.urlopen(request, timeout=90) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as error:
        raw = error.read()
        try:
            return error.code, json.loads(raw) if raw else None
        except ValueError:
            return error.code, raw[:300].decode(errors="replace")


def uid(*parts: object) -> uuid.UUID:
    return uuid.uuid5(NS, ":".join(str(p) for p in parts))


# ----------------------------------------------------------------------------------------------- owner and venue

def owner_token() -> str:
    auth = {"Authorization": API_KEY}
    status, body = http("POST", f"{FUSIONAUTH}/api/user/registration/{OWNER_ID}", {
        "skipRegistrationVerification": True,
        "skipVerification": True,
        "user": {"email": OWNER_EMAIL, "password": OWNER_PASSWORD, "firstName": "Demo", "lastName": "La Sala"},
        "registration": {"applicationId": APP_ID},
    }, auth)
    if status == 200:
        say(f"demo owner created: {OWNER_EMAIL}")
    elif status == 400:  # already exists: make sure it is registered to the app
        status, _ = http("GET", f"{FUSIONAUTH}/api/user/registration/{OWNER_ID}/{APP_ID}", headers=auth)
        if status == 404:
            status, body = http("POST", f"{FUSIONAUTH}/api/user/registration/{OWNER_ID}",
                                {"registration": {"applicationId": APP_ID}}, auth)
        if status != 200:
            fail(f"demo owner registration failed (HTTP {status}): {body}")
    else:
        fail(f"cannot create the demo owner (HTTP {status}): {body}")
    status, body = http("POST", f"{FUSIONAUTH}/api/login",
                        {"loginId": OWNER_EMAIL, "password": OWNER_PASSWORD, "applicationId": APP_ID}, auth)
    if status != 200 or not isinstance(body, dict) or "token" not in body:
        fail(f"demo owner login failed (HTTP {status}); if you changed DEMO_OWNER_PASSWORD after the first run, "
             "put the old one back")
    return body["token"]


def api(method: str, path: str, token: str, body: dict | None = None) -> tuple[int, object]:
    return http(method, BACKEND + path, body, {"Authorization": f"Bearer {token}"})


def ensure_venue(token: str) -> None:
    status, body = api("GET", "/api/mi-establecimiento", token)
    if status == 200:
        return
    if status != 404:
        fail(f"cannot read the demo venue (HTTP {status}): {body}")
    status, body = api("POST", "/api/mi-establecimiento", token, {"nombre": VENUE_NAME, "qrSlug": VENUE_SLUG})
    if status == 409:  # slug taken by another account: let the backend generate one
        status, body = api("POST", "/api/mi-establecimiento", token, {"nombre": VENUE_NAME})
    if status != 201:
        fail(f"cannot create the demo venue (HTTP {status}): {body}")
    say(f"venue created: {VENUE_NAME}")


def collect_songs(token: str) -> list[str]:
    """Video ids of real songs, through the app's own endpoints (chart first, then owner searches)."""
    status, body = http("GET", f"{BACKEND}/api/public/tendencias")
    if status != 200:
        fail("the YouTube catalog is not available (HTTP %s: %s). Set YOUTUBE_API_KEY in deploy/config.env, "
             "run the deploy again and then the demo command." % (status, body))
    chart = [c["videoId"] for c in body.get("canciones", [])]
    found: list[str] = []
    for query in SEARCHES:
        status, body = api("GET", "/api/mi-establecimiento/catalogo/busqueda?q=" + urllib.request.quote(query), token)
        if status == 200:
            found += [c["videoId"] for c in body.get("resultados", [])[:5]]
        else:
            say(f"search '{query}' skipped (HTTP {status})")
        time.sleep(0.5)
    # The chart songs are stored by the trends endpoint; the searched ones get stored by the default list below.
    unique = list(dict.fromkeys(found[:25] + chart[:15] + chart[15:]))
    if len(unique) < 15:
        fail(f"only {len(unique)} songs came from the catalog; at least 15 are needed")
    return unique


def ensure_default_list(token: str, video_ids: list[str]) -> None:
    status, _ = api("GET", "/api/mi-establecimiento/lista-defecto", token)
    if status == 200:
        return
    status, body = api("PUT", "/api/mi-establecimiento/lista-defecto", token,
                       {"nombre": "Lista de la casa (demo)", "politicaRotacion": "SECUENCIAL",
                        "canciones": video_ids[:40]})
    if status not in (200, 201):
        fail(f"cannot publish the demo default list (HTTP {status}): {body}")
    say("default list published")


# ------------------------------------------------------------------------------------------------- simulation

@dataclass
class Song:
    id: uuid.UUID
    seconds: int
    weight: float


@dataclass
class Request:
    id: uuid.UUID
    song: Song
    creator: int
    created: datetime
    cycle: int
    votes: list[tuple[int, datetime]]
    block_at: datetime | None
    state: str = "PENDIENTE"
    final: datetime | None = None


@dataclass
class Night:
    sid: uuid.UUID
    opened: datetime
    closed: datetime | None  # None: the live night
    participants: list[dict] = field(default_factory=list)
    consents: list[tuple] = field(default_factory=list)
    requests: list[Request] = field(default_factory=list)
    attempts: list[tuple] = field(default_factory=list)
    blocks: list[tuple] = field(default_factory=list)


def simulate(night: Night, rng: random.Random, songs: list[Song], items: list[tuple[uuid.UUID, Song]],
             devices: list[dict], live: bool) -> None:
    end = night.closed or datetime.now(timezone.utc)
    span = (end - night.opened).total_seconds()
    sid = night.sid

    # Participants: joins mostly in the first part of the night; channel from the device.
    count = rng.randint(30, 36) if live else rng.randint(74, 86)
    for k, device in enumerate(rng.sample(devices, count)):
        joined = night.opened + timedelta(seconds=rng.betavariate(1.6, 3.2) * span * 0.85)
        pid = uid(sid, "participacion", k)
        night.participants.append({"id": pid, "device": device, "joined": joined, "alias": f"Invitado demo {k + 1:02d}"})
        night.consents.append((uid(pid, "F1"), pid, "F1", device["canal"], joined))
        if rng.random() < 0.55:
            night.consents.append((uid(pid, "F2"), pid, "F2", device["canal"], joined + timedelta(seconds=1)))
        device["uses"].append(joined)
    joined_by = sorted(range(count), key=lambda i: night.participants[i]["joined"])

    def present(at: datetime) -> list[int]:
        return [i for i in joined_by if night.participants[i]["joined"] <= at] or joined_by[:1]

    # Request creation times: busiest around the middle of the night; the live night adds a few recent ones.
    total = rng.randint(24, 28) if live else rng.randint(114, 126)
    times = sorted(night.opened + timedelta(seconds=480 + rng.betavariate(2.2, 2.0) * (span - 900)) for _ in range(total))
    if live:
        times += sorted(end - timedelta(seconds=rng.randint(60, 600)) for _ in range(5))
    supported: set[tuple[int, uuid.UUID]] = set()
    latest: dict[uuid.UUID, Request] = {}
    cycles: dict[uuid.UUID, int] = {}
    queue: list[Request] = []
    pending_times = list(times)

    def create(at: datetime) -> None:
        creator = rng.choice(present(at))
        options = []
        for song in songs:
            last = latest.get(song.id)
            busy = last is not None and (last.final is None or last.final > at)
            window = last is not None and last.final is not None and at < last.final + timedelta(minutes=30)
            if not busy and not window and (creator, song.id) not in supported:
                options.append(song)
        if not options:
            return
        song = rng.choices(options, weights=[s.weight for s in options])[0]
        cycles[song.id] = cycles.get(song.id, 0) + 1
        voters = []
        for _ in range(rng.choices(range(10), weights=[14, 16, 16, 14, 11, 9, 7, 6, 4, 3])[0]):
            at_vote = at + timedelta(seconds=rng.expovariate(1 / 420))
            voter = rng.choice(present(at_vote))
            if voter != creator and (voter, song.id) not in supported:
                supported.add((voter, song.id))
                voters.append((voter, at_vote))
        supported.add((creator, song.id))
        blocked = at + timedelta(minutes=rng.uniform(3, 12)) if rng.random() < 0.04 else None
        request = Request(uid(sid, "solicitud", len(night.requests)), song, creator, at, cycles[song.id],
                          voters, blocked)
        night.requests.append(request)
        latest[song.id] = request
        queue.append(request)

    def advance(until: datetime) -> None:
        while pending_times and pending_times[0] <= until:
            create(pending_times.pop(0))
        for request in [r for r in queue if r.block_at and r.block_at <= until]:
            queue.remove(request)
            request.state, request.final = "BLOQUEADA", request.block_at
            request.votes = [v for v in request.votes if v[1] < request.block_at]
            night.blocks.append((uid(request.id, "bloqueo"), request.song.id, request.block_at))

    def attempt(origin: str, song: Song, request: Request | None, item: uuid.UUID | None, prepared: datetime,
                outcome: str) -> datetime:
        started = prepared + timedelta(seconds=2)
        if outcome == "COMPLETADA":
            finished = started + timedelta(seconds=song.seconds)
        elif outcome == "SALTADA":
            finished = started + timedelta(seconds=int(song.seconds * rng.uniform(0.2, 0.6)))
        elif rng.random() < 0.6:  # FALLIDA before PLAYING: no start
            started, finished = None, prepared + timedelta(seconds=6)
        else:
            finished = started + timedelta(seconds=rng.randint(10, 40))
        result = outcome
        if night.closed is not None and finished > night.closed:
            result, finished = "INTERRUMPIDA_CIERRE", night.closed
            if started is not None and started > finished:
                started = None
        elif live and finished > end:
            result, finished = None, None
        night.attempts.append((uid(sid, "reproduccion", len(night.attempts)), song.id, origin,
                               request.id if request else None, item, prepared, started, finished, result))
        if request is not None:
            request.state = {"COMPLETADA": "ATENDIDA", "SALTADA": "SALTADA", "FALLIDA": "FALLIDA",
                             "INTERRUMPIDA_CIERRE": "NO_ATENDIDA_CIERRE", None: "REPRODUCIENDO"}[result]
            request.final = finished
        return finished or end

    now = night.opened + timedelta(minutes=2)
    default_index = 0
    while True:
        advance(min(now, end))
        if now >= end:
            break
        ready = [r for r in queue if r.created <= now]
        if ready:
            ready.sort(key=lambda r: (-sum(1 for v in r.votes if v[1] <= now), r.created))
            request = ready[0]
            queue.remove(request)
            request.votes = [v for v in request.votes if v[1] <= now]
            outcome = rng.choices(["COMPLETADA", "SALTADA", "FALLIDA"], weights=[80, 12, 8])[0]
            finished = attempt("SOLICITUD", request.song, request, None, now, outcome)
        else:
            item, song = items[default_index % len(items)]
            default_index += 1
            outcome = rng.choices(["COMPLETADA", "SALTADA"], weights=[90, 10])[0]
            finished = attempt("DEFECTO", song, None, item, now, outcome)
        now = finished + timedelta(seconds=4)

    for request in queue:  # still waiting
        request.votes = [v for v in request.votes if v[1] <= end]
        if live:
            request.state = "PENDIENTE"
        else:
            request.state, request.final = "NO_ATENDIDA_CIERRE", night.closed
    if live:
        waiting = sorted(queue, key=lambda r: (-1 - len(r.votes), r.created))
        if waiting:
            waiting[0].state = "RESERVADA"


# ---------------------------------------------------------------------------------------------------- storage

def store(conn: psycopg.Connection, venue: uuid.UUID, list_id: uuid.UUID, nights: list[Night],
          devices: list[dict]) -> None:
    with conn.cursor() as cur:
        cur.executemany(
            "INSERT INTO operacion.dispositivo (id, canal, creado_at, ultimo_acceso_at, expira_at) "
            "VALUES (%s, %s, %s, %s, %s) ON CONFLICT (id) DO NOTHING",
            [(d["id"], d["canal"], min(d["uses"]), max(d["uses"]), max(d["uses"]) + timedelta(hours=24))
             for d in devices if d["uses"]])
        for night in nights:
            requests = night.requests
            votes = sum(len(r.votes) + 1 for r in requests)
            cur.execute(
                "INSERT INTO operacion.sesion (id, establecimiento_id, lista_id, fecha_operativa, estado, abierta_at, "
                "cerrada_at, version, cola_version) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s) "
                "ON CONFLICT (id) DO NOTHING",
                (night.sid, venue, list_id, night.opened.astimezone(BOGOTA).date(),
                 "CERRADA" if night.closed else "ABIERTA", night.opened, night.closed,
                 2 if night.closed else 1, len(requests) + votes + len(night.attempts)))
            cur.execute(
                "INSERT INTO operacion.estacion (id, establecimiento_id, sesion_id, nombre, habilitada) "
                "VALUES (%s, %s, %s, %s, false) ON CONFLICT (id) DO NOTHING",
                (uid(night.sid, "estacion"), venue, night.sid, "Estación demo"))
            cur.executemany(
                "INSERT INTO operacion.participacion (id, sesion_id, dispositivo_id, seudonimo, ingreso_at, "
                "ultima_actividad_at) VALUES (%s, %s, %s, %s, %s, %s) ON CONFLICT (id) DO NOTHING",
                [(p["id"], night.sid, p["device"]["id"], p["alias"], p["joined"], p["joined"] + timedelta(minutes=45))
                 for p in night.participants])
            cur.executemany(
                "INSERT INTO operacion.consentimiento (id, participacion_id, politica_version, finalidad, accion, canal, "
                "registrado_at) VALUES (%s, %s, %s, %s, 'AUTORIZA', %s, %s) ON CONFLICT (id) DO NOTHING",
                [(cid, pid, POLICY_VERSION, purpose, canal, at) for cid, pid, purpose, canal, at in night.consents])
            pids = [p["id"] for p in night.participants]
            cur.executemany(
                "INSERT INTO operacion.solicitud (id, sesion_id, cancion_id, creador_id, estado, ciclo, creada_at, "
                "finalizada_at, version) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 1) ON CONFLICT (id) DO NOTHING",
                [(r.id, night.sid, r.song.id, pids[r.creator], r.state, r.cycle, r.created, r.final) for r in requests])
            vote_rows = []
            for r in requests:
                vote_rows.append((uid(r.id, "voto", "inicial"), r.id, pids[r.creator], night.sid, r.song.id, True,
                                  r.created))
                vote_rows += [(uid(r.id, "voto", voter), r.id, pids[voter], night.sid, r.song.id, False, at)
                              for voter, at in r.votes]
            cur.executemany(
                "INSERT INTO operacion.voto (id, solicitud_id, participacion_id, sesion_id, cancion_id, es_inicial, "
                "creado_at) VALUES (%s, %s, %s, %s, %s, %s, %s) ON CONFLICT (id) DO NOTHING", vote_rows)
            station = uid(night.sid, "estacion")
            cur.executemany(
                "INSERT INTO operacion.reproduccion (id, sesion_id, cancion_id, estacion_id, origen, solicitud_id, "
                "item_lista_id, preparada_at, iniciada_at, terminada_at, resultado, motivo_codigo) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s) ON CONFLICT (id) DO NOTHING",
                [(aid, night.sid, song, station, origin, request, item, prepared, started, finished, result,
                  "SALTO_MANUAL" if result == "SALTADA" else ("ERROR_REPRODUCTOR" if result == "FALLIDA" else None))
                 for aid, song, origin, request, item, prepared, started, finished, result in night.attempts])
            cur.executemany(
                "INSERT INTO operacion.bloqueo_contenido (id, establecimiento_id, cancion_id, desde_at, hasta_at, "
                "motivo_codigo) VALUES (%s, %s, %s, %s, %s, 'INAPROPIADO') ON CONFLICT (id) DO NOTHING",
                [(bid, venue, song, at, at + timedelta(hours=1)) for bid, song, at in night.blocks])


def main() -> None:
    say("DEMO DATA: demo owner, venue 'La Sala Demo' and 8 past nights + 1 open night")
    for _ in range(60):
        status, _ = http("GET", f"{BACKEND}/actuator/health")
        if status == 200:
            break
        time.sleep(5)
    token = owner_token()
    ensure_venue(token)

    db = psycopg.connect(host="postgres", dbname="yousong", user=os.environ["DB_USER"],
                         password=os.environ["DB_PASSWORD"], autocommit=False)
    with db:
        venue = db.execute("SELECT id FROM operacion.establecimiento WHERE propietario_sub = %s", (OWNER_ID,)).fetchone()
        if venue is None:
            fail("the demo venue is not in the database")
        venue = venue[0]
        closed = db.execute("SELECT count(*) FROM operacion.sesion WHERE establecimiento_id = %s AND estado = 'CERRADA'",
                            (venue,)).fetchone()[0]
        if closed >= NIGHTS:
            say(f"the demo venue already has {closed} closed nights: nothing to do")
            return

        video_ids = collect_songs(token)
        ensure_default_list(token, video_ids)
        rows = db.execute(
            "SELECT id, youtube_video_id, coalesce(duracion_proveedor_s, duracion_operativa_s) FROM operacion.cancion "
            "WHERE youtube_video_id = ANY(%s) AND disponibilidad <> 'NO_DISPONIBLE'", (video_ids,)).fetchall()
        order = {v: i for i, v in enumerate(video_ids)}
        rows.sort(key=lambda r: order[r[1]])
        songs = [Song(r[0], max(120, min(r[2] or 240, 420)), 1 / (1 + i * 0.06)) for i, r in enumerate(rows)]
        by_id = {s.id: s for s in songs}
        list_row = db.execute("SELECT id FROM operacion.lista_defecto WHERE establecimiento_id = %s "
                              "ORDER BY version DESC, creado_at DESC LIMIT 1", (venue,)).fetchone()
        if list_row is None or len(songs) < 15:
            fail(f"not enough stored songs ({len(songs)}) or no default list")
        items = [(i, by_id.get(c) or Song(c, 240, 1)) for i, c in db.execute(
            "SELECT id, cancion_id FROM operacion.item_lista WHERE lista_id = %s ORDER BY posicion", (list_row[0],))]
        say(f"{len(songs)} real songs from the catalog; generating nights")

        rng = random.Random(str(venue))
        devices = [{"id": uid(venue, "dispositivo", k), "canal": "ANDROID" if rng.random() < 0.55 else "WEB",
                    "uses": []} for k in range(DEVICE_POOL)]
        today = datetime.now(BOGOTA).date()
        dates: list[date] = []
        day = today - timedelta(days=1)
        while len(dates) < NIGHTS:
            if day.weekday() in (4, 5):  # Friday, Saturday
                dates.append(day)
            day -= timedelta(days=1)
        nights = []
        for d in sorted(dates):
            opened = datetime(d.year, d.month, d.day, 20, 0, tzinfo=BOGOTA).astimezone(timezone.utc)
            night = Night(uid(venue, "sesion", d.isoformat()), opened, opened + timedelta(hours=6))
            simulate(night, random.Random(f"{venue}:{d}"), songs, items, devices, False)
            nights.append(night)
        in_operation = db.execute("SELECT count(*) FROM operacion.sesion WHERE establecimiento_id = %s "
                                  "AND estado IN ('ABIERTA', 'PAUSADA', 'CERRANDO')", (venue,)).fetchone()[0]
        if in_operation == 0:
            now = datetime.now(timezone.utc).replace(microsecond=0)
            live = Night(uid(venue, "sesion", "en-vivo", now.isoformat()), now - timedelta(minutes=100), None)
            simulate(live, random.Random(f"{venue}:live"), songs, items, devices, True)
            nights.append(live)
        store(db, venue, list_row[0], nights, devices)

    states = {}
    results = {}
    for night in nights:
        for r in night.requests:
            states[r.state] = states.get(r.state, 0) + 1
        for a in night.attempts:
            results[a[8] or "EN_CURSO"] = results.get(a[8] or "EN_CURSO", 0) + 1
    say(f"stored {len(nights)} nights; requests by state {states}; playbacks by result {results}")
    say(f"log in at http://localhost:3000 as {OWNER_EMAIL} to see 'La Sala Demo'")


if __name__ == "__main__":
    main()
