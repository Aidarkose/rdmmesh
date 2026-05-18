"""
Pure unit-тесты helper-функций (mapping.py) — не требуют OM SDK.

Сами функции маппинга rdmmesh → OM data-types вытеснены в `mapping.py`,
чтобы тесты прогонялись на CI без `openmetadata-ingestion` (тяжёлая
зависимость, не нужна для проверки логики).
"""

from __future__ import annotations

import pytest  # noqa: F401  (используется pytest-style assertion'ами)

from metadata.ingestion.source.database.rdmmesh.mapping import (
    build_description,
    map_jsonschema_type,
    map_key_part_type,
)
from metadata.ingestion.source.database.rdmmesh.models import (
    KeyPart,
    KeySpec,
    RdmmeshCodeSet,
)

# ---------- map_jsonschema_type ----------


def test_map_jsonschema_type_basics() -> None:
    assert map_jsonschema_type("string", None, None) == "STRING"
    assert map_jsonschema_type("integer", None, None) == "BIGINT"
    assert map_jsonschema_type("number", None, None) == "DOUBLE"
    assert map_jsonschema_type("boolean", None, None) == "BOOLEAN"
    assert map_jsonschema_type("object", None, None) == "STRUCT"
    assert map_jsonschema_type("array", None, None) == "ARRAY"


def test_map_jsonschema_type_formats() -> None:
    assert map_jsonschema_type("string", "date-time", None) == "DATETIME"
    assert map_jsonschema_type("string", "datetime", None) == "DATETIME"
    assert map_jsonschema_type("string", "date", None) == "DATE"
    assert map_jsonschema_type("string", "uuid", None) == "UUID"


def test_map_jsonschema_type_enum_wins_over_type() -> None:
    assert map_jsonschema_type("string", None, ["1", "2", "3"]) == "ENUM"
    # enum даже для number — берёт ENUM
    assert map_jsonschema_type("number", None, [1.5, 2.5]) == "ENUM"


def test_map_jsonschema_type_nullable_union() -> None:
    # ["string","null"] — берём первое не-null
    assert map_jsonschema_type(["string", "null"], None, None) == "STRING"
    assert map_jsonschema_type(["null", "integer"], None, None) == "BIGINT"
    # union без null — берём первый
    assert map_jsonschema_type(["boolean", "string"], None, None) == "BOOLEAN"


def test_map_jsonschema_type_unknown_defaults_to_string() -> None:
    assert map_jsonschema_type("nonexistent", None, None) == "STRING"
    assert map_jsonschema_type(None, None, None) == "STRING"
    assert map_jsonschema_type([], None, None) == "STRING"


# ---------- map_key_part_type ----------


def test_map_key_part_type_known() -> None:
    assert map_key_part_type("STRING") == "STRING"
    assert map_key_part_type("INTEGER") == "BIGINT"
    assert map_key_part_type("NUMBER") == "DOUBLE"
    assert map_key_part_type("BOOLEAN") == "BOOLEAN"
    assert map_key_part_type("DATE") == "DATE"
    assert map_key_part_type("DATETIME") == "DATETIME"
    assert map_key_part_type("UUID") == "UUID"


def test_map_key_part_type_case_insensitive() -> None:
    assert map_key_part_type("integer") == "BIGINT"
    assert map_key_part_type("Boolean") == "BOOLEAN"


def test_map_key_part_type_defaults() -> None:
    assert map_key_part_type(None) == "STRING"
    assert map_key_part_type("CUSTOM_UNKNOWN") == "STRING"


# ---------- build_description ----------


def test_build_description_combines_fields() -> None:
    codeset = RdmmeshCodeSet(
        id="cs-1",
        domain_id="d-1",
        name="ifrs9_stages",
        description="IFRS9 SICR стадии",
        hierarchy_mode="INTRA_CODESET",
        key_spec=KeySpec(parts=[KeyPart(name="code", type="STRING")]),
    )
    out = build_description(codeset, "0.3.0")
    assert "IFRS9 SICR стадии" in out
    assert "0.3.0" in out
    assert "INTRA_CODESET" in out
    assert "code: STRING" in out


def test_build_description_composite_key() -> None:
    codeset = RdmmeshCodeSet(
        id="cs-1",
        domain_id="d-1",
        name="position_system_matrix",
        key_spec=KeySpec(
            parts=[
                KeyPart(name="position_code", type="STRING"),
                KeyPart(name="system_code", type="STRING"),
            ],
        ),
    )
    out = build_description(codeset, None)
    assert "position_code: STRING, system_code: STRING" in out


def test_build_description_skips_empty_pieces() -> None:
    codeset = RdmmeshCodeSet(
        id="cs-1",
        domain_id="d-1",
        name="x",
        hierarchy_mode="NONE",  # не должно появиться
    )
    out = build_description(codeset, None)
    assert out == ""


def test_build_description_version_only() -> None:
    codeset = RdmmeshCodeSet(id="cs-1", domain_id="d-1", name="x")
    out = build_description(codeset, "1.0.0")
    assert out == "_Published version:_ `1.0.0`"
