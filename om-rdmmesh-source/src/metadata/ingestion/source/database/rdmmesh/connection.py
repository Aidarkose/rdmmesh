"""
Connection-handler для rdmmesh source.

Идёт через `get_connection(...)` (создание/кэш клиента) и `test_connection(...)`
(шаги для test-connection automation feature в OM).
"""

from __future__ import annotations

import hashlib
import logging
from typing import TYPE_CHECKING, Any

from metadata.ingestion.source.database.rdmmesh.client import RdmmeshApiError, RdmmeshClient

if TYPE_CHECKING:  # pragma: no cover
    # При установке в OM-venv эти импорты резолвятся. Локально (без OM) —
    # коннектор не запустится, но импорт TYPE_CHECKING не мешает unit-тестам.
    from metadata.generated.schema.entity.automations.workflow import (
        Workflow as AutomationWorkflow,
    )
    from metadata.generated.schema.entity.services.connections.testConnectionResult import (
        TestConnectionResult,
    )
    from metadata.ingestion.ometa.ometa_api import OpenMetadata

logger = logging.getLogger(__name__)

_THREE_MIN = 180
_CLIENT_CACHE: dict[str, RdmmeshClient] = {}


def _config_to_dict(connection: Any) -> dict[str, Any]:
    """Pydantic-config OM → plain dict (имена-поля и SecretStr → str)."""
    if hasattr(connection, "model_dump"):
        return connection.model_dump(mode="python")  # pydantic v2
    if hasattr(connection, "dict"):
        return connection.dict()  # pydantic v1, на всякий случай
    return dict(connection)  # type: ignore[arg-type]


def _make_client(connection: Any) -> RdmmeshClient:
    cfg = _config_to_dict(connection)
    client_secret = cfg.get("clientSecret")
    if hasattr(client_secret, "get_secret_value"):
        client_secret = client_secret.get_secret_value()
    return RdmmeshClient(
        host_port=cfg["hostPort"],
        keycloak_issuer_uri=cfg["keycloakIssuerUri"],
        client_id=cfg["clientId"],
        client_secret=client_secret or "",
        request_timeout_seconds=cfg.get("requestTimeoutSeconds"),
        verify_ssl=cfg.get("verifySSL", True),
    )


def get_connection(connection: Any) -> RdmmeshClient:
    """
    Создать / отдать кэшированный клиент.

    Кэш по SHA-256 от сериализованного config'а — OM создаёт новый Pydantic-объект
    на каждой десериализации, поэтому id() ненадёжен (см. паттерн burstiq).
    """
    payload = repr(_config_to_dict(connection)).encode()
    key = hashlib.sha256(payload).hexdigest()
    if key not in _CLIENT_CACHE:
        _CLIENT_CACHE[key] = _make_client(connection)
    return _CLIENT_CACHE[key]


def test_connection(
    metadata: OpenMetadata,
    client: RdmmeshClient,
    service_connection: Any,
    automation_workflow: AutomationWorkflow | None = None,
    timeout_seconds: int | None = _THREE_MIN,
) -> TestConnectionResult:
    """Шаги test-connection: auth → list_domains → list_codesets."""
    # late import — нужен только когда метод реально вызывается из OM
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
        service_type=getattr(getattr(service_connection, "type", None), "value", "Rdmmesh"),
        automation_workflow=automation_workflow,
        timeout_seconds=timeout_seconds,
    )
