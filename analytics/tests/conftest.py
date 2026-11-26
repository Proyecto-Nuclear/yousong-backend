from datetime import date, datetime, timezone

import pytest

from yousong_analytics.spark import create_spark


@pytest.fixture(scope="session")
def spark():
    session = create_spark("yousong-analytics-tests")
    yield session
    session.stop()


def utc(text):
    """ISO-8601 instant (UTC) as an aware datetime."""
    return datetime.fromisoformat(text.replace("Z", "+00:00")).astimezone(timezone.utc)


EST = "e0000000-0000-4000-8000-000000000001"
LISTA = "10000000-0000-4000-8000-000000000001"
S1 = "51000000-0000-4000-8000-000000000001"  # closed session
S2 = "52000000-0000-4000-8000-000000000002"  # open session
C1, C2, C3 = ("c1000000-0000-4000-8000-000000000001", "c2000000-0000-4000-8000-000000000002",
              "c3000000-0000-4000-8000-000000000003")
REF_A, REF_B, REF_C, REF_D = ("a" * 64, "b" * 64, "c" * 64, "d" * 64)


def snapshot_rows():
    """A closed night (S1) with every request/playback outcome and an open night (S2)."""
    return {
        "establecimiento": [{"id": EST, "nombre": "Local", "zona_horaria": "America/Bogota"}],
        "sesion": [
            {"id": S1, "establecimiento_id": EST, "lista_id": LISTA, "fecha_operativa": date(2026, 11, 20),
             "estado": "CERRADA", "abierta_at": utc("2026-11-21T00:30:00Z"), "cerrada_at": utc("2026-11-21T06:00:00Z")},
            {"id": S2, "establecimiento_id": EST, "lista_id": LISTA, "fecha_operativa": date(2026, 11, 21),
             "estado": "ABIERTA", "abierta_at": utc("2026-11-22T00:30:00Z"), "cerrada_at": None},
        ],
        "lista": [{"id": LISTA, "establecimiento_id": EST, "nombre": "Repertorio general", "version": 1}],
        "participacion": [
            {"sesion_id": S1, "participante_ref": REF_A, "canal": "WEB", "ingreso_at": utc("2026-11-21T00:40:00Z")},
            {"sesion_id": S1, "participante_ref": REF_B, "canal": "ANDROID", "ingreso_at": utc("2026-11-21T00:41:00Z")},
            {"sesion_id": S1, "participante_ref": REF_C, "canal": "WEB", "ingreso_at": utc("2026-11-21T00:42:00Z")},
            {"sesion_id": S2, "participante_ref": REF_D, "canal": "ANDROID", "ingreso_at": utc("2026-11-22T00:40:00Z")},
        ],
        "cancion": [
            {"id": C1, "genero_propio": "Salsa", "fuente_clasificacion": "Revisión del local"},
            {"id": C2, "genero_propio": None, "fuente_clasificacion": None},
            {"id": C3, "genero_propio": "Rock", "fuente_clasificacion": "Revisión del local"},
        ],
        "solicitud": [
            sol("a1000000-0000-4000-8000-000000000001", S1, C1, REF_A, "ATENDIDA", "2026-11-21T01:00:00Z"),
            sol("a2000000-0000-4000-8000-000000000002", S1, C2, REF_B, "SALTADA", "2026-11-21T01:05:00Z"),
            sol("a3000000-0000-4000-8000-000000000003", S1, C3, REF_A, "FALLIDA", "2026-11-21T04:59:00Z"),
            sol("a4000000-0000-4000-8000-000000000004", S1, C1, REF_B, "NO_ATENDIDA_CIERRE", "2026-11-21T05:30:00Z"),
            sol("a5000000-0000-4000-8000-000000000005", S1, C2, REF_A, "NO_ATENDIDA_CIERRE", "2026-11-21T05:40:00Z"),
            sol("a6000000-0000-4000-8000-000000000006", S2, C1, REF_D, "REPRODUCIENDO", "2026-11-22T01:00:00Z"),
            sol("a7000000-0000-4000-8000-000000000007", S2, C2, REF_D, "PENDIENTE", "2026-11-22T01:10:00Z"),
        ],
        "voto": [
            voto("b1000000-0000-4000-8000-000000000001", "a1000000-0000-4000-8000-000000000001", S1, C1, REF_A, True,
                 "2026-11-21T01:00:00Z"),
            voto("b2000000-0000-4000-8000-000000000002", "a1000000-0000-4000-8000-000000000001", S1, C1, REF_B, False,
                 "2026-11-21T01:02:00Z"),
            voto("b3000000-0000-4000-8000-000000000003", "a2000000-0000-4000-8000-000000000002", S1, C2, REF_B, True,
                 "2026-11-21T01:05:00Z"),
            voto("b4000000-0000-4000-8000-000000000004", "a6000000-0000-4000-8000-000000000006", S2, C1, REF_D, True,
                 "2026-11-22T01:00:00Z"),
        ],
        "reproduccion": [
            rep("f1000000-0000-4000-8000-000000000001", S1, C1, "SOLICITUD", "a1000000-0000-4000-8000-000000000001",
                None, "2026-11-21T01:10:00Z", "2026-11-21T01:10:02.500Z", "2026-11-21T01:14:00Z", "COMPLETADA"),
            rep("f2000000-0000-4000-8000-000000000002", S1, C2, "SOLICITUD", "a2000000-0000-4000-8000-000000000002",
                None, "2026-11-21T01:15:00Z", "2026-11-21T01:15:00Z", "2026-11-21T01:16:00Z", "SALTADA"),
            # Failure before PLAYING: never started.
            rep("f3000000-0000-4000-8000-000000000003", S1, C3, "SOLICITUD", "a3000000-0000-4000-8000-000000000003",
                None, "2026-11-21T05:00:00Z", None, "2026-11-21T05:00:30Z", "FALLIDA"),
            rep("f4000000-0000-4000-8000-000000000004", S1, C1, "SOLICITUD", "a4000000-0000-4000-8000-000000000004",
                None, "2026-11-21T05:55:00Z", "2026-11-21T05:55:01Z", "2026-11-21T06:00:00Z", "INTERRUMPIDA_CIERRE"),
            rep("f5000000-0000-4000-8000-000000000005", S1, C3, "DEFECTO", None, LISTA,
                "2026-11-21T00:31:00Z", "2026-11-21T00:31:00Z", "2026-11-21T00:35:00Z", "COMPLETADA"),
            # In progress.
            rep("f6000000-0000-4000-8000-000000000006", S2, C1, "SOLICITUD", "a6000000-0000-4000-8000-000000000006",
                None, "2026-11-22T01:20:00Z", "2026-11-22T01:20:00Z", None, None),
        ],
    }


def sol(id_, sesion, cancion, creador, estado, creada):
    return {"id": id_, "sesion_id": sesion, "cancion_id": cancion, "creador_ref": creador, "estado": estado,
            "creada_at": utc(creada)}


def voto(id_, solicitud, sesion, cancion, participante, inicial, creado):
    return {"id": id_, "solicitud_id": solicitud, "sesion_id": sesion, "cancion_id": cancion,
            "participante_ref": participante, "es_inicial": inicial, "creado_at": utc(creado)}


def rep(id_, sesion, cancion, origen, solicitud, lista, preparada, iniciada, terminada, resultado):
    return {"id": id_, "sesion_id": sesion, "cancion_id": cancion, "origen": origen, "solicitud_id": solicitud,
            "lista_id": lista, "preparada_at": utc(preparada), "iniciada_at": utc(iniciada) if iniciada else None,
            "terminada_at": utc(terminada) if terminada else None, "resultado": resultado}
