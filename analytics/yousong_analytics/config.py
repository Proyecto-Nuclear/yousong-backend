"""Pipeline configuration. Read only from environment variables; secrets are never printed."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from typing import Mapping

from .pseudonym import MIN_KEY_BYTES


class ConfigError(ValueError):
    """Invalid or missing configuration. Messages name variables, never their values."""


REQUIRED = ("ANALYTICS_DB_URL", "ANALYTICS_DB_USER", "ANALYTICS_DB_PASSWORD", "ANALYTICS_HMAC_KEY",
            "ANALYTICS_PARQUET_DIR")


@dataclass(frozen=True)
class Config:
    db_url: str
    db_user: str
    db_password: str = field(repr=False)
    hmac_key: bytes = field(repr=False)
    parquet_dir: str = ""
    interval_seconds: int = 300
    lag_seconds: int = 30

    def __str__(self) -> str:
        return repr(self)

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> "Config":
        env = os.environ if env is None else env
        missing = [name for name in REQUIRED if not env.get(name)]
        if missing:
            raise ConfigError("Missing environment variables: " + ", ".join(missing))
        key = env["ANALYTICS_HMAC_KEY"].encode("utf-8")
        if len(key) < MIN_KEY_BYTES:
            raise ConfigError(f"ANALYTICS_HMAC_KEY must have at least {MIN_KEY_BYTES} bytes")
        url = env["ANALYTICS_DB_URL"].strip()
        if url.startswith("jdbc:"):
            url = url[len("jdbc:"):]
        return cls(
            db_url=url,
            db_user=env["ANALYTICS_DB_USER"],
            db_password=env["ANALYTICS_DB_PASSWORD"],
            hmac_key=key,
            parquet_dir=env["ANALYTICS_PARQUET_DIR"],
            interval_seconds=_positive(env, "ANALYTICS_INTERVAL_SECONDS", 300),
            lag_seconds=_non_negative(env, "ANALYTICS_LAG_SECONDS", 30),
        )


def _int(env: Mapping[str, str], name: str, default: int) -> int:
    raw = env.get(name)
    if raw is None or raw == "":
        return default
    try:
        return int(raw)
    except ValueError as error:
        raise ConfigError(f"{name} must be an integer") from error


def _positive(env: Mapping[str, str], name: str, default: int) -> int:
    value = _int(env, name, default)
    if value <= 0:
        raise ConfigError(f"{name} must be greater than zero")
    return value


def _non_negative(env: Mapping[str, str], name: str, default: int) -> int:
    value = _int(env, name, default)
    if value < 0:
        raise ConfigError(f"{name} must not be negative")
    return value
