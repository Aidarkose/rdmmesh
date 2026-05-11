"""
Service spec для rdmmesh — entry point, который OM находит по dotted-path.

Шаблон: `metadata.ingestion.source.database.{service_name}.service_spec.ServiceSpec`
(см. `metadata.utils.service_spec.service_spec.BaseSpec`).

На E12-MVP отдаём только metadata_source. Lineage / profiler / test-suite —
вне scope (rdmmesh — это иерархия metadata-только, без табличных данных).
"""

from __future__ import annotations

from metadata.utils.service_spec.default import DefaultDatabaseSpec

from metadata.ingestion.source.database.rdmmesh.metadata import RdmmeshSource

ServiceSpec = DefaultDatabaseSpec(
    metadata_source_class=RdmmeshSource,
)
