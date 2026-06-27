package bank.rdmmesh.catalog.internal.dao;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO для {@code catalog.om_role} — зеркала ролей OpenMetadata. Наполняется pull'ом
 * по уведомлению OM Alert (см. {@code CatalogSyncService}). Identity — {@code om_role_id}.
 */
public interface OmRoleDao {

    String COLUMNS =
            "id, om_role_id, name, display_name, description,"
                    + " last_om_sync_at, created_at, updated_at, deleted_at";

    @SqlQuery("SELECT " + COLUMNS + " FROM catalog.om_role WHERE om_role_id = :omRoleId")
    @RegisterConstructorMapper(OmRoleRow.class)
    Optional<OmRoleRow> findByOmId(@Bind("omRoleId") UUID omRoleId);

    /**
     * UPSERT роли из OM. Идемпотентно по {@code om_role_id}. Resurrect: deleted_at
     * сбрасывается в NULL, last_om_sync_at обновляется.
     */
    @SqlUpdate(
            """
            INSERT INTO catalog.om_role
                (om_role_id, name, display_name, description)
            VALUES
                (:omRoleId, :name, :displayName, :description)
            ON CONFLICT (om_role_id) DO UPDATE
              SET name            = EXCLUDED.name,
                  display_name    = EXCLUDED.display_name,
                  description     = EXCLUDED.description,
                  deleted_at      = NULL,
                  last_om_sync_at = now(),
                  updated_at      = now()
            """)
    int upsertByOmId(
            @Bind("omRoleId") UUID omRoleId,
            @Bind("name") String name,
            @Bind("displayName") String displayName,
            @Bind("description") String description);

    /** Snapshot строки роли — внутренний транспорт между DAO и mapper'ом. */
    record OmRoleRow(
            UUID id,
            UUID omRoleId,
            String name,
            String displayName,
            String description,
            Instant lastOmSyncAt,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt) {}
}
