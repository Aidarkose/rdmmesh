"""
Pure helpers — маппинг rdmmesh → OM значений, **без зависимостей от OM SDK**.

Выделены в отдельный модуль, чтобы:
- юнит-тесты прогонялись на CI без `openmetadata-ingestion`;
- логику маппинга было видно одним файлом, без шума wrappers вокруг Column.

Использование Column из OM, обёртка `Markdown(...)` и build-FQN — остаются
в `metadata.py` (это уже не pure).
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any

from metadata.ingestion.source.database.rdmmesh.models import (
    CodeSetRef,
    RdmmeshCodeSet,
)


def map_jsonschema_type(
    json_type: str | list[str] | None,
    fmt: str | None,
    enum: list[Any] | None,
) -> str:
    """JSON Schema property → OpenMetadata Column dataType (string)."""
    if enum:
        return "ENUM"
    if isinstance(json_type, list):
        # nullable union: ["string","null"] — берём первое не "null"
        non_null = [t for t in json_type if t != "null"]
        json_type = non_null[0] if non_null else "string"
    if json_type == "string":
        if fmt in ("date-time", "datetime"):
            return "DATETIME"
        if fmt == "date":
            return "DATE"
        if fmt == "uuid":
            return "UUID"
        return "STRING"
    if json_type == "integer":
        return "BIGINT"
    if json_type == "number":
        return "DOUBLE"
    if json_type == "boolean":
        return "BOOLEAN"
    if json_type == "object":
        return "STRUCT"
    if json_type == "array":
        return "ARRAY"
    return "STRING"


def map_key_part_type(type_str: str | None) -> str:
    """KeySpec.parts[].type → OM Column dataType."""
    return {
        "STRING": "STRING",
        "INTEGER": "BIGINT",
        "NUMBER": "DOUBLE",
        "BOOLEAN": "BOOLEAN",
        "DATE": "DATE",
        "DATETIME": "DATETIME",
        "UUID": "UUID",
    }.get((type_str or "STRING").upper(), "STRING")


def build_description(codeset: RdmmeshCodeSet, version_str: str | None) -> str:
    """
    Markdown-описание таблицы из метаданных CodeSet.

    Собирает в `description` всё, что в OM Table негде разместить
    структурно: версию, hierarchy mode, описание ключа.
    """
    parts: list[str] = []
    if codeset.description:
        parts.append(codeset.description)
    if version_str:
        parts.append(f"_Published version:_ `{version_str}`")
    if codeset.hierarchy_mode and codeset.hierarchy_mode != "NONE":
        parts.append(f"_Hierarchy:_ `{codeset.hierarchy_mode}`")
    if codeset.key_spec and codeset.key_spec.parts:
        key_repr = ", ".join(
            f"{p.name}: {p.type}" for p in codeset.key_spec.parts
        )
        parts.append(f"_Key:_ `({key_repr})`")
    return "\n\n".join(parts)


def build_column_fqn(table_fqn: str, column: str) -> str:
    """OM column FQN = <table_fqn>.<column>. Колонки rdmmesh — snake_case без точек."""
    return f"{table_fqn}.{column}"


def build_fk_constraint_specs(
    references: list[CodeSetRef],
    resolve_table_fqn: Callable[[str], str | None],
) -> list[dict[str, list[str]]]:
    """rdmmesh CodeSet.references → список «спеков» FOREIGN_KEY-констрейнтов.

    Pure: не зависит от OM SDK — возвращает простые dict'ы
    ``{"columns": [...], "referred_columns": [...]}``. metadata.py превращает их в
    OM ``TableConstraint``. `resolve_table_fqn(to_codeset_id)` отдаёт FQN таблицы
    целевого справочника (может быть в другом домене) или ``None``, если справочник
    не найден/удалён — такой ref тихо пропускается (graceful degradation, как E20).
    """
    specs: list[dict[str, list[str]]] = []
    for ref in references or []:
        target_table_fqn = resolve_table_fqn(ref.to_codeset_id)
        if not target_table_fqn:
            continue
        specs.append(
            {
                "columns": [ref.from_column],
                "referred_columns": [
                    build_column_fqn(target_table_fqn, ref.to_column)
                ],
            }
        )
    return specs


__all__ = [
    "map_jsonschema_type",
    "map_key_part_type",
    "build_description",
    "build_column_fqn",
    "build_fk_constraint_specs",
]
