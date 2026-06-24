"""
REST-клиент к rdmmesh с Keycloak client_credentials.

Контракт rdmmesh REST — см. SPEC §3.5 и handoff E3/E4/E5/E6:
- GET /api/v1/domains
- GET /api/v1/codesets/by-domain/{domainId}
- GET /api/v1/codesets/{id}
- GET /api/v1/codesets/{id}/schema
- GET /api/v1/versions/by-codeset/{id}

Аутентификация — Bearer JWT от Keycloak realm `bank`, client `rdmmesh-backend`
(confidential, serviceAccountsEnabled=true). Токен кэшируется + рефрешится за 60s
до истечения.
"""

from __future__ import annotations

import logging
import time
from datetime import datetime, timedelta, timezone
from typing import Any

import requests
from pydantic import SecretStr

from metadata.ingestion.source.database.rdmmesh.models import (
    RdmmeshCodeSet,
    RdmmeshCodeSetSchema,
    RdmmeshCodeSetVersion,
    RdmmeshDomain,
    TokenResponse,
)

logger = logging.getLogger(__name__)

_TOKEN_REFRESH_SKEW_SECONDS = 60
_DEFAULT_TIMEOUT = (10, 30)


class RdmmeshAuthError(RuntimeError):
    """Keycloak вернул не-2xx на client_credentials."""


class RdmmeshApiError(RuntimeError):
    """rdmmesh REST вернул не-2xx."""


class RdmmeshClient:
    """
    Тонкий REST-клиент с lazy-аутентификацией.

    Не thread-safe; в OM ingestion это и не нужно (одна сессия на workflow).
    """

    def __init__(
        self,
        host_port: str,
        keycloak_issuer_uri: str,
        client_id: str,
        client_secret: str | SecretStr,
        request_timeout_seconds: int | None = None,
        verify_ssl: bool = True,
    ) -> None:
        self._host_port = host_port.rstrip("/")
        self._issuer = keycloak_issuer_uri.rstrip("/")
        self._client_id = client_id
        self._client_secret = (
            client_secret.get_secret_value()
            if isinstance(client_secret, SecretStr)
            else client_secret
        )
        self._timeout: tuple[int, int] = (
            (_DEFAULT_TIMEOUT[0], request_timeout_seconds)
            if request_timeout_seconds
            else _DEFAULT_TIMEOUT
        )
        self._verify_ssl = verify_ssl

        self._session = requests.Session()
        self._access_token: str | None = None
        self._token_expires_at: datetime | None = None

    # ---------- authentication ----------

    def authenticate(self) -> None:
        """Forсированный re-auth. Полезен для test_connection."""
        self._access_token = None
        self._token_expires_at = None
        self._ensure_token()

    def _ensure_token(self) -> str:
        now = datetime.now(timezone.utc)
        if (
            self._access_token
            and self._token_expires_at
            and now < self._token_expires_at
        ):
            return self._access_token

        token_url = f"{self._issuer}/protocol/openid-connect/token"
        payload = {
            "grant_type": "client_credentials",
            "client_id": self._client_id,
            "client_secret": self._client_secret,
        }
        logger.debug("rdmmesh: requesting client_credentials token at %s", token_url)
        start = time.time()
        try:
            response = self._session.post(
                token_url,
                data=payload,
                headers={
                    "Content-Type": "application/x-www-form-urlencoded",
                    "Accept": "application/json",
                },
                timeout=self._timeout,
                verify=self._verify_ssl,
            )
        except requests.RequestException as exc:
            raise RdmmeshAuthError(f"network error при обращении к Keycloak: {exc}") from exc

        elapsed = time.time() - start
        if not response.ok:
            raise RdmmeshAuthError(
                f"Keycloak token endpoint вернул {response.status_code}: {response.text[:500]}"
            )

        token = TokenResponse.model_validate(response.json())
        self._access_token = token.access_token
        self._token_expires_at = datetime.now(timezone.utc) + timedelta(
            seconds=max(token.expires_in - _TOKEN_REFRESH_SKEW_SECONDS, 30)
        )
        logger.debug(
            "rdmmesh: получен access_token, expires_in=%ds (auth took %.2fs)",
            token.expires_in,
            elapsed,
        )
        return self._access_token

    # ---------- HTTP plumbing ----------

    def _get(self, path: str) -> Any:
        token = self._ensure_token()
        url = f"{self._host_port}{path}"
        headers = {
            "Authorization": f"Bearer {token}",
            "Accept": "application/json",
        }
        try:
            response = self._session.get(
                url,
                headers=headers,
                timeout=self._timeout,
                verify=self._verify_ssl,
            )
        except requests.RequestException as exc:
            raise RdmmeshApiError(f"network error при GET {url}: {exc}") from exc

        # 401 — попробовать один раз пере-аутентифицироваться (например, token истёк раньше TTL).
        if response.status_code == 401:
            logger.info("rdmmesh: получен 401, повторная аутентификация")
            self._access_token = None
            token = self._ensure_token()
            headers["Authorization"] = f"Bearer {token}"
            response = self._session.get(
                url, headers=headers, timeout=self._timeout, verify=self._verify_ssl
            )

        if not response.ok:
            raise RdmmeshApiError(
                f"rdmmesh REST вернул {response.status_code} на GET {url}: {response.text[:500]}"
            )

        if not response.content:
            return None
        return response.json()

    # ---------- typed endpoints ----------

    def list_domains(self) -> list[RdmmeshDomain]:
        data = self._get("/api/v1/domains")
        items = data if isinstance(data, list) else (data or {}).get("items", [])
        return [RdmmeshDomain.model_validate(item) for item in items]

    def list_codesets(self, domain_id: str) -> list[RdmmeshCodeSet]:
        data = self._get(f"/api/v1/codesets/by-domain/{domain_id}")
        items = data if isinstance(data, list) else (data or {}).get("items", [])
        return [RdmmeshCodeSet.model_validate(item) for item in items]

    def get_codeset(self, codeset_id: str) -> RdmmeshCodeSet | None:
        data = self._get(f"/api/v1/codesets/{codeset_id}")
        if not data:
            return None
        return RdmmeshCodeSet.model_validate(data)

    def get_codeset_schema(self, codeset_id: str) -> RdmmeshCodeSetSchema | None:
        data = self._get(f"/api/v1/codesets/{codeset_id}/schema")
        if not data:
            return None
        # API E3 возвращает { codeset_id, version, json_schema }
        return RdmmeshCodeSetSchema.model_validate(data)

    def list_versions(self, codeset_id: str) -> list[RdmmeshCodeSetVersion]:
        data = self._get(f"/api/v1/versions/by-codeset/{codeset_id}")
        items = data if isinstance(data, list) else (data or {}).get("items", [])
        return [RdmmeshCodeSetVersion.model_validate(item) for item in items]

    def latest_published(self, codeset_id: str) -> RdmmeshCodeSetVersion | None:
        """Последняя версия в статусе PUBLISHED (если есть). E6 семантика."""
        versions = self.list_versions(codeset_id)
        published = [v for v in versions if v.status == "PUBLISHED"]
        if not published:
            return None
        # Отсортируем по published_at DESC; tie-break по semver.
        published.sort(
            key=lambda v: (v.published_at or datetime.min.replace(tzinfo=timezone.utc), v.version),
            reverse=True,
        )
        return published[0]

    # ---------- close ----------

    def close(self) -> None:
        self._session.close()
        self._access_token = None
        self._token_expires_at = None


__all__ = [
    "RdmmeshApiError",
    "RdmmeshAuthError",
    "RdmmeshClient",
]
