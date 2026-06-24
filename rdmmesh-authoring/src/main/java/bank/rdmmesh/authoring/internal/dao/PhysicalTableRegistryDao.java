package bank.rdmmesh.authoring.internal.dao;

import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * Реестр материализованных физических таблиц справочников ({@code authoring.codeset_physical_table}).
 * Хранит, в какую таблицу схемы {@code rd_data} материализован каждый CodeSet (relational store, V024).
 */
public interface PhysicalTableRegistryDao {

    @SqlQuery(
            "SELECT codeset_id, schema_name, table_name, schema_version, published_version_id"
                    + " FROM authoring.codeset_physical_table WHERE codeset_id = :codesetId")
    @RegisterConstructorMapper(PhysicalTableRow.class)
    Optional<PhysicalTableRow> findByCodeset(@Bind("codesetId") UUID codesetId);

    @SqlUpdate(
            """
            INSERT INTO authoring.codeset_physical_table
                (codeset_id, schema_name, table_name, schema_version)
            VALUES (:codesetId, :schemaName, :tableName, :schemaVersion)
            ON CONFLICT (codeset_id) DO UPDATE
               SET schema_name    = EXCLUDED.schema_name,
                   table_name     = EXCLUDED.table_name,
                   schema_version = EXCLUDED.schema_version,
                   updated_at     = now()
            """)
    int upsert(
            @Bind("codesetId") UUID codesetId,
            @Bind("schemaName") String schemaName,
            @Bind("tableName") String tableName,
            @Bind("schemaVersion") int schemaVersion);

    /** Фиксирует, какая версия сейчас материализована в "<base>__current". */
    @SqlUpdate(
            "UPDATE authoring.codeset_physical_table"
                    + " SET published_version_id = :versionId, updated_at = now()"
                    + " WHERE codeset_id = :codesetId")
    int setPublishedVersion(
            @Bind("codesetId") UUID codesetId, @Bind("versionId") UUID versionId);

    record PhysicalTableRow(
            UUID codesetId,
            String schemaName,
            String tableName,
            int schemaVersion,
            UUID publishedVersionId) {}
}
