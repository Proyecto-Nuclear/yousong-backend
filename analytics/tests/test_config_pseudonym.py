import hashlib
import hmac

import pytest

from yousong_analytics.config import Config, ConfigError
from yousong_analytics.pseudonym import participante_ref

KEY = "k" * 40
ENV = {
    "ANALYTICS_DB_URL": "postgresql://db:5432/nuclear_vii",
    "ANALYTICS_DB_USER": "analytics",
    "ANALYTICS_DB_PASSWORD": "s3cret-password",
    "ANALYTICS_HMAC_KEY": KEY,
    "ANALYTICS_PARQUET_DIR": "/data/parquet",
}


def test_config_reads_only_environment_variables():
    config = Config.from_env(ENV)
    assert config.db_url == "postgresql://db:5432/nuclear_vii"
    assert config.db_user == "analytics"
    assert config.hmac_key == KEY.encode()
    assert config.parquet_dir == "/data/parquet"
    assert config.interval_seconds == 300
    assert config.lag_seconds == 30


def test_config_accepts_a_jdbc_url():
    config = Config.from_env({**ENV, "ANALYTICS_DB_URL": "jdbc:postgresql://db:5432/nuclear_vii"})
    assert config.db_url == "postgresql://db:5432/nuclear_vii"


def test_config_never_shows_secrets():
    text = repr(Config.from_env(ENV)) + str(Config.from_env(ENV))
    assert "s3cret-password" not in text
    assert KEY not in text


def test_missing_variables_are_named_without_values():
    with pytest.raises(ConfigError) as error:
        Config.from_env({"ANALYTICS_DB_PASSWORD": "s3cret-password"})
    message = str(error.value)
    assert "ANALYTICS_DB_URL" in message and "ANALYTICS_HMAC_KEY" in message
    assert "s3cret-password" not in message


def test_short_hmac_key_is_rejected():
    with pytest.raises(ConfigError, match="ANALYTICS_HMAC_KEY"):
        Config.from_env({**ENV, "ANALYTICS_HMAC_KEY": "short"})


def test_interval_must_be_positive():
    with pytest.raises(ConfigError, match="ANALYTICS_INTERVAL_SECONDS"):
        Config.from_env({**ENV, "ANALYTICS_INTERVAL_SECONDS": "0"})


def test_participante_ref_is_hmac_sha256_of_session_and_participation():
    sesion = "11111111-1111-1111-1111-111111111111"
    participacion = "22222222-2222-2222-2222-222222222222"
    expected = hmac.new(KEY.encode(), f"{sesion}:{participacion}".encode(), hashlib.sha256).hexdigest()
    ref = participante_ref(KEY.encode(), sesion, participacion)
    assert ref == expected
    assert len(ref) == 64 and all(c in "0123456789abcdef" for c in ref)


def test_participante_ref_changes_per_session_and_key():
    participacion = "22222222-2222-2222-2222-222222222222"
    a = participante_ref(KEY.encode(), "11111111-1111-1111-1111-111111111111", participacion)
    b = participante_ref(KEY.encode(), "33333333-3333-3333-3333-333333333333", participacion)
    c = participante_ref(("x" * 40).encode(), "11111111-1111-1111-1111-111111111111", participacion)
    assert len({a, b, c}) == 3
