"""
Тесты парсера `connectionOptions` (CustomDatabaseConnection-based config).

Все приватные helper'ы в `connection.py` принимают duck-typed объекты
(`hasattr(opts, "root")` либо dict напрямую) — это покрывает оба сценария:
реальный CustomDatabaseConnection-Pydantic-объект из OM и моки в тестах.
"""

from __future__ import annotations

from typing import Any

import pytest

from metadata.ingestion.source.database.rdmmesh.client import RdmmeshClient
from metadata.ingestion.source.database.rdmmesh.connection import (
    _make_client,
    _options,
    _parse_bool,
    _parse_int,
    _require,
    get_connection,
)


class _FakeOpts:
    """Имитирует `CustomDatabaseConnection.connectionOptions` (Pydantic RootModel)."""

    def __init__(self, root: dict[str, str]) -> None:
        self.root = root


class _FakeConn:
    def __init__(self, root: dict[str, str]) -> None:
        self.connectionOptions = _FakeOpts(root)


def _full_opts(**overrides: Any) -> dict[str, str]:
    base = {
        "hostPort": "http://rdmmesh.local:8080",
        "keycloakIssuerUri": "http://kc.local/realms/bank",
        "clientId": "rdmmesh-backend",
        "clientSecret": "s3cr3t",
    }
    base.update({k: str(v) for k, v in overrides.items()})
    return base


# ---------- _options ----------


def test_options_extracts_root_dict() -> None:
    conn = _FakeConn({"hostPort": "http://x", "verifySSL": "false"})
    assert _options(conn) == {"hostPort": "http://x", "verifySSL": "false"}


def test_options_returns_empty_when_no_attr() -> None:
    class _NoOpts:
        pass

    assert _options(_NoOpts()) == {}


def test_options_handles_plain_dict() -> None:
    # Если объект сам по себе dict-подобен (для совместимости в редких случаях)
    class _DictLike(dict):  # type: ignore[type-arg]
        pass

    obj = _DictLike()
    obj["k"] = 1
    # _options не достанет это (нет .connectionOptions) — возвращает {}
    assert _options(obj) == {}


def test_options_coerces_values_to_str() -> None:
    conn = _FakeConn({"requestTimeoutSeconds": 30})  # type: ignore[dict-item]
    assert _options(conn) == {"requestTimeoutSeconds": "30"}


# ---------- _require ----------


def test_require_returns_value() -> None:
    assert _require({"a": "x"}, "a") == "x"


def test_require_raises_on_missing() -> None:
    with pytest.raises(ValueError) as exc:
        _require({}, "hostPort")
    assert "hostPort" in str(exc.value)


def test_require_raises_on_empty_string() -> None:
    with pytest.raises(ValueError):
        _require({"hostPort": ""}, "hostPort")


# ---------- _parse_bool / _parse_int ----------


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("true", True),
        ("TRUE", True),
        (" YES ", True),
        ("1", True),
        ("on", True),
        ("false", False),
        ("0", False),
        ("no", False),
        ("garbage", False),
    ],
)
def test_parse_bool(raw: str, expected: bool) -> None:
    assert _parse_bool(raw, default=False) is expected


def test_parse_bool_default_when_none() -> None:
    assert _parse_bool(None, default=True) is True
    assert _parse_bool("", default=False) is False


@pytest.mark.parametrize("raw,expected", [("30", 30), ("0", 0), ("-5", -5)])
def test_parse_int_ok(raw: str, expected: int) -> None:
    assert _parse_int(raw, default=None) == expected


def test_parse_int_default() -> None:
    assert _parse_int(None, default=10) == 10
    assert _parse_int("", default=10) == 10


def test_parse_int_raises_on_garbage() -> None:
    with pytest.raises(ValueError):
        _parse_int("not-a-number", default=None)


# ---------- _make_client / get_connection ----------


def test_make_client_full_options() -> None:
    conn = _FakeConn(_full_opts(requestTimeoutSeconds=45, verifySSL=False))
    client = _make_client(conn)
    assert isinstance(client, RdmmeshClient)
    # Внутренние поля проверять не хотим (private), но клиент должен быть собран.


def test_make_client_clientId_default_when_missing() -> None:
    opts = _full_opts()
    opts.pop("clientId", None)
    conn = _FakeConn(opts)
    client = _make_client(conn)
    assert isinstance(client, RdmmeshClient)


def test_make_client_raises_when_required_missing() -> None:
    opts = _full_opts()
    opts.pop("clientSecret")
    conn = _FakeConn(opts)
    with pytest.raises(ValueError) as exc:
        _make_client(conn)
    assert "clientSecret" in str(exc.value)


def test_get_connection_caches_by_options_hash() -> None:
    conn1 = _FakeConn(_full_opts())
    conn2 = _FakeConn(_full_opts())  # тот же конфиг, новый объект — должен попасть в кэш
    client1 = get_connection(conn1)
    client2 = get_connection(conn2)
    assert client1 is client2


def test_get_connection_returns_fresh_for_different_options() -> None:
    conn_a = _FakeConn(_full_opts(hostPort="http://a:8080"))
    conn_b = _FakeConn(_full_opts(hostPort="http://b:8080"))
    a = get_connection(conn_a)
    b = get_connection(conn_b)
    assert a is not b
