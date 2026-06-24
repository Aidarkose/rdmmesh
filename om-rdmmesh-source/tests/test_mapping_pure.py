"""
Pure unit-тесты helper-функций (mapping.py) — не требуют OM SDK.

Сами функции маппинга rdmmesh → OM data-types вытеснены в `mapping.py`,
чтобы тесты прогонялись на CI без `openmetadata-ingestion` (тяжёлая
зависимость, не нужна для проверки логики).
"""

from __future__ import annotations

import pytest  # noqa: F401  (используется pytest-style assertion'ами)

from metadata.ingestion.source.database.rdmmesh.mapping import (
    build_column_fqn,
    build_description,
    build_fk_constraint_specs,
    map_jsonschema_type,
    map_key_part_type,
)
from metadata.ingestion.source.database.rdmmesh.models import (
    CodeSetRef,
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


# ---------- build_column_fqn ----------


def test_build_column_fqn() -> None:
    assert (
        build_column_fqn("svc.default.r_branch.r_ecl_branch_sgmnt", "id")
        == "svc.default.r_branch.r_ecl_branch_sgmnt.id"
    )


# ---------- build_fk_constraint_specs ----------


def _fqn(codeset_id: str) -> str | None:
    # Фейковый резолвер: id → FQN таблицы (или None для несуществующих).
    table = {
        "cs-branch-sgmnt": "svc.default.r_branch.r_ecl_branch_sgmnt",
        "cs-prdct-sgmnt": "svc.default.r_product.r_lnk_prdct_to_ecl_sgmnt",
    }.get(codeset_id)
    return table


def test_build_fk_constraint_specs_resolves() -> None:
    refs = [
        CodeSetRef(
            from_column="branch_sgmnt_id",
            to_codeset_id="cs-branch-sgmnt",
            to_column="id",
        ),
        CodeSetRef(
            from_column="product_id",
            to_codeset_id="cs-prdct-sgmnt",
            to_column="r_ecl_prdct_sgmnt",
        ),
    ]
    specs = build_fk_constraint_specs(refs, _fqn)
    assert specs == [
        {
            "columns": ["branch_sgmnt_id"],
            "referred_columns": ["svc.default.r_branch.r_ecl_branch_sgmnt.id"],
        },
        {
            "columns": ["product_id"],
            "referred_columns": [
                "svc.default.r_product.r_lnk_prdct_to_ecl_sgmnt.r_ecl_prdct_sgmnt"
            ],
        },
    ]


def test_build_fk_constraint_specs_skips_unresolved() -> None:
    # Цель не резолвится (удалён/не найден) → ref тихо пропускается.
    refs = [
        CodeSetRef(from_column="x_id", to_codeset_id="missing", to_column="id"),
        CodeSetRef(
            from_column="branch_sgmnt_id",
            to_codeset_id="cs-branch-sgmnt",
            to_column="id",
        ),
    ]
    specs = build_fk_constraint_specs(refs, _fqn)
    assert specs == [
        {
            "columns": ["branch_sgmnt_id"],
            "referred_columns": ["svc.default.r_branch.r_ecl_branch_sgmnt.id"],
        }
    ]


def test_build_fk_constraint_specs_empty() -> None:
    assert build_fk_constraint_specs([], _fqn) == []
