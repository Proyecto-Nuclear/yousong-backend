"""Stable analytic keys. The same business identity always yields the same key, so retries never duplicate."""

import uuid

# Fixed namespace of the YouSong analytic model (never change it: existing keys depend on it).
NAMESPACE = uuid.UUID("5b1d3f0e-7c1a-4b8e-9a43-0f6c2d9e8a11")


def dim_cancion_id(establecimiento_id: str, cancion_ref: str) -> str:
    """Key of the business identity UNIQUE(establecimiento_id, cancion_ref)."""
    return str(uuid.uuid5(NAMESPACE, f"dim_cancion:{establecimiento_id}:{cancion_ref}"))


def dim_participacion_id(sesion_id: str, participante_ref: str) -> str:
    """Key of the business identity UNIQUE(sesion_id, participante_ref); derived from the pseudonym only."""
    return str(uuid.uuid5(NAMESPACE, f"dim_participacion:{sesion_id}:{participante_ref}"))
