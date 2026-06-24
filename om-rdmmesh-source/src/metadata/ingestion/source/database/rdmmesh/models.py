"""
Pydantic-модели wire-формата rdmmesh REST API.

Узкий набор полей: только то, что нужно ingestion-коннектору для построения
OM-сущностей (Domain → DatabaseSchema, CodeSet → Table, attribute → Column).
Полная JSON Schema живёт в `rdmmesh-spec/schema/entity/*.json`; при необходимости
расширения — либо добавлять поле сюда вручную, либо переключиться на codegen
(см. README §Codegen).
"""

from __future__ import annotations

from datetime import datetime
from typing import Any

from pydantic import BaseModel, ConfigDict, Field


class _Base(BaseModel):
    """Базовый класс: snake_case wire-формат, разрешён extra-keys (forward-compat)."""

    model_config = ConfigDict(extra="allow", populate_by_name=True)


# ---------- Keycloak token ----------


class TokenResponse(_Base):
    access_token: str
    expires_in: int = 300
    token_type: str = "Bearer"


# ---------- rdmmesh domain ----------


class RdmmeshDomain(_Base):
    id: str
    om_domain_id: str | None = None
    name: str
    display_name: str | None = None
    description: str | None = None
    label_ru: str | None = None
    label_en: str | None = None
    tags: list[str] = Field(default_factory=list)
    deleted_at: datetime | None = None


# ---------- rdmmesh codeset + schema ----------


class KeyPart(_Base):
    name: str
    type: str = "STRING"


class KeySpec(_Base):
    parts: list[KeyPart] = Field(default_factory=list)


class CodeSetRef(_Base):
    """Cross-codeset FK-связь: колонка этого справочника → колонка другого.

    См. rdmmesh-spec/schema/entity/code-set.json#/properties/references.
    `to_codeset_id` может указывать на справочник в другом домене.
    """

    from_column: str
    to_codeset_id: str
    to_column: str


class RdmmeshCodeSet(_Base):
    id: str
    domain_id: str
    name: str
    display_name: str | None = None
    description: str | None = None
    label_ru: str | None = None
    label_en: str | None = None
    tags: list[str] = Field(default_factory=list)
    hierarchy_mode: str | None = None  # NONE | INTRA_CODESET | CROSS_CODESET
    key_spec: KeySpec | None = None
    references: list[CodeSetRef] = Field(default_factory=list)
    last_published_version: str | None = None
    deleted_at: datetime | None = None


class RdmmeshCodeSetSchema(_Base):
    """`/api/v1/codesets/{id}/schema` — wrapper над свободным JSON Schema document."""

    codeset_id: str | None = None
    version: int | None = None
    json_schema: dict[str, Any] = Field(default_factory=dict)


# ---------- rdmmesh versions ----------


class RdmmeshCodeSetVersion(_Base):
    id: str
    codeset_id: str
    version: str  # semver
    status: str  # DRAFT | IN_REVIEW | ... | PUBLISHED | DEPRECATED
    content_hash: str | None = None
    published_at: datetime | None = None
    deprecated_at: datetime | None = None
