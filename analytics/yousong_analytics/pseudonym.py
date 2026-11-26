"""Session-scoped pseudonym of a participation (dim_participacion.participante_ref)."""

import hashlib
import hmac

MIN_KEY_BYTES = 32


def participante_ref(key: bytes, sesion_id: str, participacion_id: str) -> str:
    """HMAC-SHA-256 of "sesion_id:participacion_id" as 64 lowercase hex characters.

    Not reversible without the key, and different for the same device in another session. The key comes from
    the environment and is never written to the dataset or the logs.
    """
    message = f"{sesion_id}:{participacion_id}".encode("utf-8")
    return hmac.new(key, message, hashlib.sha256).hexdigest()
