"""
Connection-handler для rdmmesh source (vanilla-OM подход).

Используем стандартный OM `CustomDatabaseConnection` + `connectionOptions`
(Map<String,String>) — никаких правок в `openmetadata-spec`.

Все наши настройки приходят строками через `connectionOptions.root`:
- hostPort                : URL rdmmesh REST (обязательное)
- keycloakIssuerUri       : Keycloak realm issuer URI (обязательное)
- clientId                : OIDC client_id (по умолчанию: rdmmesh-backend)
- clientSecret            : OIDC client_secret (обязательное)
- requestTimeoutSeconds   : опционально, "30"
- verifySSL               : опционально, "true"/"false"
"""

from __future__ import annotations

import hashlib
import json
import logging
from typing import TYPE_CHECKING, Any

from metadata.ingestion.source.database.rdmmesh.client import RdmmeshApiError, RdmmeshClient

if TYPE_CHECKING:  # pragma: no cover
    from metadata.generated.schema.entity.automations.workflow import (
        Workflow as AutomationWorkflow,
    )
    from metadata.generated.schema.entity.services.connections.database.customDatabaseConnection import (
        CustomDatabaseConnection,
    )
    from metadata.generated.schema.entity.services.connections.testConnectionResult import (
        TestConnectionResult,
    )
    from metadata.ingestion.ometa.ometa_api import OpenMetadata

logger = logging.getLogger(__name__)

_THREE_MIN = 180
_CLIENT_CACHE: dict[str, RdmmeshClient] = {}


def _options(connection: Any) -> dict[str, str]:
    """Извлечь `connectionOptions.root` как обычный dict[str, str]."""
    opts = getattr(connection, "connectionOptions", None)
    if opts is None:
        return {}
    # Pydantic v2 RootModel: .root — это нижележащий dict.
    root = getattr(opts, "root", None)
    if isinstance(root, dict):
        return {str(k): str(v) for k, v in root.items()}
    # Fallback на случай прямого dict (например, в unit-тестах).
    if isinstance(opts, dict):
        return {str(k): str(v) for k, v in opts.items()}
    return {}


def _require(opts: dict[str, str], key: str) -> str:
    value = opts.get(key)
    if not value:
        raise ValueError(
            f"connectionOptions.{key} не задан — обязательное поле для RdmmeshSource"
        )
    return value


def _parse_bool(raw: str | None, default: bool) -> bool:
    if raw is None or raw == "":
        return default
    return raw.strip().lower() in ("true", "1", "yes", "on")


def _parse_int(raw: str | None, default: int | None) -> int | None:
    if raw is None or raw == "":
        return default
    try:
        return int(raw)
    except ValueError as exc:
        raise ValueError(f"connectionOptions: ожидался int, получили {raw!r}") from exc


def _make_client(connection: Any) -> RdmmeshClient:
    opts = _options(connection)
    return RdmmeshClient(
        host_port=_require(opts, "hostPort"),
        keycloak_issuer_uri=_require(opts, "keycloakIssuerUri"),
        client_id=opts.get("clientId") or "rdmmesh-backend",
        client_secret=_require(opts, "clientSecret"),
        request_timeout_seconds=_parse_int(opts.get("requestTimeoutSeconds"), None),
        verify_ssl=_parse_bool(opts.get("verifySSL"), True),
    )


def get_connection(connection: Any) -> RdmmeshClient:
    """
    Создать / отдать кэшированный клиент.

    Кэш по SHA-256 от сериализованного `connectionOptions` — OM создаёт новый
    Pydantic-объект на каждой десериализации, поэтому `id()` ненадёжен
    (см. паттерн burstiq).
    """
    opts = _options(connection)
    payload = json.dumps(opts, sort_keys=True).encode()
    key = hashlib.sha256(payload).hexdigest()
    if key not in _CLIENT_CACHE:
        _CLIENT_CACHE[key] = _make_client(connection)
    return _CLIENT_CACHE[key]


def test_connection(
    metadata: OpenMetadata,
    client: RdmmeshClient,
    service_connection: CustomDatabaseConnection,
    automation_workflow: AutomationWorkflow | None = None,
    timeout_seconds: int | None = _THREE_MIN,
) -> TestConnectionResult:
    """Шаги test-connection: auth → list_domains → list_codesets."""
    from metadata.ingestion.connections.test_connections import (
        test_connection_steps,  # type: ignore[import-not-found]
    )

    def check_auth() -> None:
        client.authenticate()

    def check_list_domains() -> None:
        domains = client.list_domains()
        logger.info("rdmmesh: получили %d доменов при test_connection", len(domains))

    def check_list_codesets() -> None:
        domains = client.list_domains()
        if not domains:
            return
        sample = domains[0]
        try:
            codesets = client.list_codesets(sample.id)
            logger.info(
                "rdmmesh: домен %s содержит %d CodeSet'ов", sample.name, len(codesets)
            )
        except RdmmeshApiError as exc:
            logger.warning("rdmmesh: list_codesets для %s упал: %s", sample.name, exc)

    test_fn = {
        "CheckAccess": check_auth,
        "ListDomains": check_list_domains,
        "ListCodeSets": check_list_codesets,
    }
    return test_connection_steps(
        metadata=metadata,
        test_fn=test_fn,
        service_type="CustomDatabase",
        automation_workflow=automation_workflow,
        timeout_seconds=timeout_seconds,
    )
