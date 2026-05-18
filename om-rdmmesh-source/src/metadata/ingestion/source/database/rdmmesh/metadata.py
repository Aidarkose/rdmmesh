"""
rdmmesh database service source для OpenMetadata.

Маппинг (см. README + SPEC §3.6):
    rdmmesh Domain   → OM DatabaseSchema  (rdmmesh.default.<domain_name>)
    rdmmesh CodeSet  → OM Table           (rdmmesh.default.<domain>.<codeset>)
    CodeSetSchema    → Column[]
    PUBLISHED semver → Table.version
    deleted_at       → markAsDeleted

Owners/experts/reviewers НЕ переносятся (SPEC §2.4 — назначаются в OM UI,
текут обратно в rdmmesh через E7 webhook).

Topology DatabaseServiceSource (см. OM `database_service.py`):
    get_database_names → yield_database
        get_database_schema_names → yield_database_schema
            get_tables_name_and_type → yield_table
"""

from __future__ import annotations

import logging
import traceback
from collections.abc import Iterable
from typing import Any

from metadata.generated.schema.api.data.createDatabase import CreateDatabaseRequest
from metadata.generated.schema.api.data.createDatabaseSchema import (
    CreateDatabaseSchemaRequest,
)
from metadata.generated.schema.api.data.createStoredProcedure import (
    CreateStoredProcedureRequest,
)
from metadata.generated.schema.api.data.createTable import CreateTableRequest
from metadata.generated.schema.entity.data.database import Database
from metadata.generated.schema.entity.data.databaseSchema import DatabaseSchema
from metadata.generated.schema.entity.data.table import Column, Table, TableType
from metadata.generated.schema.entity.services.connections.database.customDatabaseConnection import (
    CustomDatabaseConnection,
)
from metadata.generated.schema.entity.services.connections.metadata.openMetadataConnection import (
    OpenMetadataConnection,
)
from metadata.generated.schema.entity.services.ingestionPipelines.status import (
    StackTraceError,
)
from metadata.generated.schema.metadataIngestion.databaseServiceMetadataPipeline import (
    DatabaseServiceMetadataPipeline,
)
from metadata.generated.schema.metadataIngestion.workflow import (
    Source as WorkflowSource,
)
from metadata.generated.schema.type.basic import (
    EntityName,
    FullyQualifiedEntityName,
    Markdown,
)
from metadata.ingestion.api.models import Either
from metadata.ingestion.api.steps import InvalidSourceException
from metadata.ingestion.models.ometa_classification import OMetaTagAndClassification
from metadata.ingestion.ometa.ometa_api import OpenMetadata
from metadata.ingestion.source.database.database_service import DatabaseServiceSource
from metadata.utils import fqn
from metadata.utils.filters import filter_by_schema, filter_by_table

from metadata.ingestion.source.database.rdmmesh.client import (
    RdmmeshApiError,
    RdmmeshClient,
)
from metadata.ingestion.source.database.rdmmesh.connection import get_connection
from metadata.ingestion.source.database.rdmmesh.mapping import (
    build_description,
    map_jsonschema_type,
    map_key_part_type,
)
from metadata.ingestion.source.database.rdmmesh.models import (
    RdmmeshCodeSet,
    RdmmeshCodeSetSchema,
    RdmmeshDomain,
)

logger = logging.getLogger(__name__)

_DEFAULT_DATABASE = "default"


class RdmmeshSource(DatabaseServiceSource):
    """OM source-коннектор rdmmesh."""

    def __init__(self, config: WorkflowSource, metadata: OpenMetadata) -> None:
        super().__init__()
        self.config = config
        self.metadata = metadata
        self.source_config: DatabaseServiceMetadataPipeline = (
            self.config.sourceConfig.config  # type: ignore[assignment]
        )
        self.service_connection: CustomDatabaseConnection = (
            self.config.serviceConnection.root.config  # type: ignore[union-attr,assignment]
        )
        self.client: RdmmeshClient = get_connection(self.service_connection)

        # Кэши «текущего цикла ingestion'а» — заполняются по мере спуска по топологии.
        self._domains_by_name: dict[str, RdmmeshDomain] = {}
        self._codesets_by_name: dict[str, RdmmeshCodeSet] = {}

        self.connection_obj = self.client
        self.test_connection()

    # ---------- entrypoint ----------

    @classmethod
    def create(
        cls,
        config_dict: dict[str, Any],
        metadata: OpenMetadataConnection,
        pipeline_name: str | None = None,
    ) -> RdmmeshSource:
        config = WorkflowSource.model_validate(config_dict)
        connection = config.serviceConnection.root.config  # type: ignore[union-attr]
        # vanilla-OM подход: используем generic CustomDatabaseConnection +
        # sourcePythonClass-discovery. Никаких правок в openmetadata-spec.
        if not isinstance(connection, CustomDatabaseConnection):
            raise InvalidSourceException(
                "RdmmeshSource ожидает type=CustomDatabase, получили "
                f"{type(connection).__name__}"
            )
        return cls(config, metadata)  # type: ignore[arg-type]

    # ---------- database (синтетический) ----------

    def get_database_names(self) -> Iterable[str]:
        # Только один синтетический database = "default" (SPEC §3.6 опускает database-уровень
        # в FQN, мы держим его константой; service=<service-name from workflow>).
        yield _DEFAULT_DATABASE

    def yield_database(
        self, database_name: str
    ) -> Iterable[Either[CreateDatabaseRequest]]:
        database_request = CreateDatabaseRequest(
            name=EntityName(database_name),
            service=FullyQualifiedEntityName(self.context.get().database_service),
        )
        yield Either(right=database_request)
        self.register_record_database_request(database_request=database_request)

    # ---------- schemas (один на rdmmesh domain) ----------

    def get_database_schema_names(self) -> Iterable[str]:
        """Один OM DatabaseSchema на каждый rdmmesh domain."""
        try:
            domains = self.client.list_domains()
        except RdmmeshApiError as exc:
            logger.error("rdmmesh: list_domains упал: %s", exc)
            raise

        for domain in domains:
            if domain.deleted_at is not None:
                # soft-deleted в rdmmesh → OM сам пометит markAsDeleted при следующем
                # цикле через mark_deleted_entities (SourceConfig.markDeletedTables).
                continue
            self._domains_by_name[domain.name] = domain

            if filter_by_schema(
                self.source_config.schemaFilterPattern,
                domain.name,
            ):
                self.status.filter(domain.name, "Domain filtered out")
                continue

            yield domain.name

    def yield_database_schema(
        self, schema_name: str
    ) -> Iterable[Either[CreateDatabaseSchemaRequest]]:
        domain = self._domains_by_name.get(schema_name)
        description = _markdown_or_none(
            domain.description if domain else None
        )
        display_name = (
            domain.display_name if domain and domain.display_name else None
        )

        schema_request = CreateDatabaseSchemaRequest(
            name=EntityName(schema_name),
            displayName=display_name,
            description=description,
            database=FullyQualifiedEntityName(
                fqn.build(
                    metadata=self.metadata,
                    entity_type=Database,
                    service_name=self.context.get().database_service,
                    database_name=self.context.get().database,
                )
            ),
        )
        yield Either(right=schema_request)
        self.register_record_schema_request(schema_request=schema_request)

    # ---------- tables (один на CodeSet) ----------

    def get_tables_name_and_type(self) -> Iterable[tuple[str, str]]:
        schema_name = self.context.get().database_schema
        domain = self._domains_by_name.get(schema_name)
        if domain is None:
            logger.warning("rdmmesh: schema=%s не найден в кеше доменов", schema_name)
            return

        if not self.source_config.includeTables:
            return

        # Cache reset per schema.
        self._codesets_by_name.clear()

        try:
            codesets = self.client.list_codesets(domain.id)
        except RdmmeshApiError as exc:
            logger.error(
                "rdmmesh: list_codesets для domain %s упал: %s", domain.name, exc
            )
            raise

        for codeset in codesets:
            if codeset.deleted_at is not None:
                continue
            self._codesets_by_name[codeset.name] = codeset

            table_fqn = fqn.build(
                self.metadata,
                entity_type=Table,
                service_name=self.context.get().database_service,
                database_name=self.context.get().database,
                schema_name=self.context.get().database_schema,
                table_name=codeset.name,
                skip_es_search=True,
            )
            if filter_by_table(
                self.source_config.tableFilterPattern,
                table_fqn if self.source_config.useFqnForFiltering else codeset.name,
            ):
                self.status.filter(table_fqn, "CodeSet filtered out")
                continue

            yield codeset.name, TableType.Regular.value

    def yield_table(
        self, table_name_and_type: tuple[str, TableType]
    ) -> Iterable[Either[CreateTableRequest]]:
        table_name, table_type = table_name_and_type
        schema_name = self.context.get().database_schema
        db_name = self.context.get().database

        codeset = self._codesets_by_name.get(table_name)
        if codeset is None:
            err = f"CodeSet {table_name!r} не закэширован — пропускаем"
            logger.error(err)
            yield Either(
                left=StackTraceError(
                    name=table_name, error=err, stackTrace=traceback.format_exc()
                )
            )
            return

        try:
            schema_doc = self.client.get_codeset_schema(codeset.id)
        except RdmmeshApiError as exc:
            err = f"get_codeset_schema({codeset.id}) упал: {exc}"
            logger.error(err)
            yield Either(
                left=StackTraceError(
                    name=table_name, error=err, stackTrace=traceback.format_exc()
                )
            )
            return

        columns = _build_columns(schema_doc, codeset)
        version_str = codeset.last_published_version
        if not version_str:
            try:
                latest = self.client.latest_published(codeset.id)
                version_str = latest.version if latest else None
            except RdmmeshApiError as exc:
                logger.warning(
                    "rdmmesh: latest_published(%s) упал: %s — отдадим без version",
                    codeset.id,
                    exc,
                )

        description_text = build_description(codeset, version_str)
        request = CreateTableRequest(
            name=EntityName(table_name),
            tableType=table_type,
            description=_markdown_or_none(description_text),
            columns=columns,
            databaseSchema=FullyQualifiedEntityName(
                fqn.build(
                    metadata=self.metadata,
                    entity_type=DatabaseSchema,
                    service_name=self.context.get().database_service,
                    database_name=db_name,
                    schema_name=schema_name,
                )
            ),
        )
        yield Either(right=request)
        self.register_record(table_request=request)

    # ---------- stubs (rdmmesh не имеет stored procedures / тегов на E12-MVP) ----------

    def get_stored_procedures(self) -> Iterable[Any]:
        return []

    def yield_stored_procedure(
        self, stored_procedure: Any
    ) -> Iterable[Either[CreateStoredProcedureRequest]]:
        return []

    def yield_tag(
        self, schema_name: str
    ) -> Iterable[Either[OMetaTagAndClassification]]:
        # Tags из rdmmesh приходят как Table.tags при yield_table; отдельных классификаций пока нет.
        return []

    def close(self) -> None:
        try:
            self.client.close()
        except Exception:  # noqa: BLE001 — close best-effort
            logger.debug("rdmmesh: close() игнорирован: %s", traceback.format_exc())


# ---------- helpers ----------


def _markdown_or_none(text: str | None) -> Markdown | None:
    if not text:
        return None
    return Markdown(text)


def _build_columns(
    schema_doc: RdmmeshCodeSetSchema | None, codeset: RdmmeshCodeSet
) -> list[Column]:
    """JSON Schema properties → OM Columns. Плюс ключевые части — отдельные columns с NOT_NULL."""
    columns: list[Column] = []
    order = 0

    # 1. Ключевые части как key columns (всегда NOT NULL).
    if codeset.key_spec and codeset.key_spec.parts:
        from metadata.generated.schema.entity.data.table import Constraint

        for part in codeset.key_spec.parts:
            columns.append(
                Column(
                    name=part.name[:256],
                    dataType=map_key_part_type(part.type),
                    dataLength=1,
                    constraint=Constraint.NOT_NULL,
                    ordinalPosition=order,
                    description=Markdown("Key part"),
                )
            )
            order += 1

    # 2. Аттрибуты CodeItem из CodeSetSchema.json_schema.properties.
    if schema_doc and schema_doc.json_schema:
        props = (schema_doc.json_schema or {}).get("properties", {}) or {}
        required = set((schema_doc.json_schema or {}).get("required", []) or [])
        for prop_name, prop_def in props.items():
            if not isinstance(prop_def, dict):
                continue
            columns.append(_build_column_from_property(prop_name, prop_def, prop_name in required, order))
            order += 1

    return columns


def _build_column_from_property(
    name: str, definition: dict[str, Any], is_required: bool, order: int
) -> Column:
    from metadata.generated.schema.entity.data.table import Constraint

    json_type = definition.get("type")
    fmt = definition.get("format")
    enum = definition.get("enum")
    description = definition.get("description")

    data_type = map_jsonschema_type(json_type, fmt, enum)
    data_length = 1
    column_props: dict[str, Any] = {
        "name": name[:256],
        "dataType": data_type,
        "dataLength": data_length,
        "ordinalPosition": order,
    }
    if enum:
        column_props["dataType"] = "ENUM"
        column_props["arrayDataType"] = None
    if description:
        column_props["description"] = Markdown(description)
    if is_required:
        column_props["constraint"] = Constraint.NOT_NULL
    # Nested objects → STRUCT с children (упрощённо; глубокая рекурсия — V1+).
    if json_type == "object":
        children: list[Column] = []
        sub_props = definition.get("properties", {}) or {}
        sub_required = set(definition.get("required", []) or [])
        sub_order = 0
        for sub_name, sub_def in sub_props.items():
            if not isinstance(sub_def, dict):
                continue
            children.append(
                _build_column_from_property(
                    sub_name, sub_def, sub_name in sub_required, sub_order
                )
            )
            sub_order += 1
        if children:
            column_props["children"] = children
    return Column(**column_props)


