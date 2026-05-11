"""
Pure helpers — маппинг rdmmesh → OM значений, **без зависимостей от OM SDK**.

Выделены в отдельный модуль, чтобы:
- юнит-тесты прогонялись на CI без `openmetadata-ingestion`;
- логику маппинга было видно одним файлом, без шума wrappers вокруг Column.

Использование Column из OM, обёртка `Markdown(...)` и build-FQN — остаются
в `metadata.py` (это уже не pure).
"""

from __future__ import annotations

from typing import Any

from metadata.ingestion.source.database.rdmmesh.models import RdmmeshCodeSet


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


__all__ = ["map_jsonschema_type", "map_key_part_type", "build_description"]
