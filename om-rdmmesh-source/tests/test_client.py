"""
Unit-тесты RdmmeshClient — без OM SDK, только requests + responses.

Покрывают:
- Keycloak client_credentials happy-path + кэш токена + auto-refresh при 401.
- list_domains / list_codesets — desearialize JSON в Pydantic-модели.
- latest_published сортирует по published_at DESC.
- Ошибочные ответы (401 Keycloak, 5xx rdmmesh).
"""

from __future__ import annotations

from datetime import datetime, timezone

import pytest
import responses

from metadata.ingestion.source.database.rdmmesh.client import (
    RdmmeshApiError,
    RdmmeshAuthError,
    RdmmeshClient,
)

HOST = "http://rdmmesh.test"
ISSUER = "http://kc.test/realms/bank"
TOKEN_URL = f"{ISSUER}/protocol/openid-connect/token"


def _client() -> RdmmeshClient:
    return RdmmeshClient(
        host_port=HOST,
        keycloak_issuer_uri=ISSUER,
        client_id="rdmmesh-backend",
        client_secret="secret",
        request_timeout_seconds=5,
    )


def _mock_token(rsps: responses.RequestsMock, *, token: str = "t1", expires_in: int = 300) -> None:
    rsps.add(
        responses.POST,
        TOKEN_URL,
        json={"access_token": token, "expires_in": expires_in, "token_type": "Bearer"},
        status=200,
    )


@responses.activate
def test_authenticate_caches_token() -> None:
    _mock_token(responses, token="abc", expires_in=300)
    responses.add(
        responses.GET,
        f"{HOST}/api/v1/domains",
        json=[],
        status=200,
    )
    responses.add(
        responses.GET,
        f"{HOST}/api/v1/domains",
        json=[],
        status=200,
    )

    client = _client()
    client.list_domains()
    client.list_domains()  # второй вызов — токен из кэша

    token_calls = [c for c in responses.calls if c.request.url == TOKEN_URL]
    assert len(token_calls) == 1, "ожидаем один token-request на два list_domains"


@responses.activate
def test_keycloak_500_raises_auth_error() -> None:
    responses.add(responses.POST, TOKEN_URL, status=500, body="boom")
    client = _client()
    with pytest.raises(RdmmeshAuthError) as exc_info:
        client.authenticate()
    assert "500" in str(exc_info.value)


@responses.activate
def test_rdmmesh_401_triggers_reauth_then_succeeds() -> None:
    _mock_token(responses, token="t-old")
    # Первый ответ — 401, второй (после re-auth) — 200.
    responses.add(responses.GET, f"{HOST}/api/v1/domains", status=401, body="expired")
    _mock_token(responses, token="t-new")
    responses.add(responses.GET, f"{HOST}/api/v1/domains", json=[], status=200)

    client = _client()
    out = client.list_domains()
    assert out == []
    # Должно быть два POST token-call'а (initial + re-auth) и два GET'а.
    assert sum(1 for c in responses.calls if c.request.url == TOKEN_URL) == 2


@responses.activate
def test_list_domains_parses_payload() -> None:
    _mock_token(responses)
    responses.add(
        responses.GET,
        f"{HOST}/api/v1/domains",
        json=[
            {
                "id": "d1",
                "om_domain_id": "om-1",
                "name": "risk",
                "display_name": "Risk Department",
                "description": "Управление рисками",
                "tags": ["regulatory"],
            },
            {"id": "d2", "name": "treasury"},
        ],
        status=200,
    )

    client = _client()
    domains = client.list_domains()
    assert [d.name for d in domains] == ["risk", "treasury"]
    assert domains[0].display_name == "Risk Department"
    assert domains[0].tags == ["regulatory"]


@responses.activate
def test_latest_published_picks_most_recent_published() -> None:
    _mock_token(responses)
    responses.add(
        responses.GET,
        f"{HOST}/api/v1/versions/by-codeset/cs-1",
        json=[
            {"id": "v1", "codeset_id": "cs-1", "version": "0.1.0", "status": "DRAFT"},
            {
                "id": "v2",
                "codeset_id": "cs-1",
                "version": "0.2.0",
                "status": "PUBLISHED",
                "published_at": "2026-05-01T10:00:00Z",
            },
            {
                "id": "v3",
                "codeset_id": "cs-1",
                "version": "0.3.0",
                "status": "PUBLISHED",
                "published_at": "2026-05-04T12:00:00Z",
            },
        ],
        status=200,
    )

    client = _client()
    latest = client.latest_published("cs-1")
    assert latest is not None
    assert latest.version == "0.3.0"
    assert latest.published_at == datetime(2026, 5, 4, 12, 0, tzinfo=timezone.utc)


@responses.activate
def test_5xx_raises_api_error() -> None:
    _mock_token(responses)
    responses.add(responses.GET, f"{HOST}/api/v1/domains", status=503, body="busy")

    client = _client()
    with pytest.raises(RdmmeshApiError):
        client.list_domains()
