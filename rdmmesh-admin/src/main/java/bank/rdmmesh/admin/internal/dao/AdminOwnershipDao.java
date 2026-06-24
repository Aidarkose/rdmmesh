package bank.rdmmesh.admin.internal.dao;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO для admin-операций на {@code ownership.rdm_asset_ownership} — RDM-локальные
 * назначения owner/steward (origin='RDM') и pinned_local-флаг. Параллелен
 * {@code ownership.internal.dao.AssetOwnershipDao}: тот для webhook-mirror'а (origin='OM').
 */
public interface AdminOwnershipDao {

    String COLUMNS =
            "id, asset_id, asset_type, om_user_id, role, is_provisional, assigned_at,"
                    + " assigned_by, source_event_id, origin, pinned_local,"
                    + " assigned_by_user_id, superseded_at";

    @SqlQuery(
            "SELECT " + COLUMNS + " FROM ownership.rdm_asset_ownership"
                    + " WHERE asset_id = :assetId AND asset_type = :assetType"
                    + " AND superseded_at IS NULL"
                    + " ORDER BY role, om_user_id")
    @RegisterConstructorMapper(OwnershipRow.class)
    List<OwnershipRow> findActive(
            @Bind("assetId") UUID assetId, @Bind("assetType") String assetType);

    @SqlQuery("SELECT " + COLUMNS + " FROM ownership.rdm_asset_ownership WHERE id = :id")
    @RegisterConstructorMapper(OwnershipRow.class)
    Optional<OwnershipRow> findById(@Bind("id") UUID id);

    /**
     * Локальное назначение admin'ом. {@code origin='RDM'}, {@code is_provisional=false}.
     * Идемпотентно: при попытке повторно вставить тот же tuple — реактивируем
     * superseded_at IS NULL (если был soft-удалён).
     */
    @SqlUpdate(
            """
            INSERT INTO ownership.rdm_asset_ownership
                (id, asset_id, asset_type, om_user_id, role, is_provisional,
                 assigned_at, origin, pinned_local, assigned_by_user_id, superseded_at)
            VALUES (:id, :assetId, :assetType, :omUserId, :role, false,
                    now(), 'RDM', :pinnedLocal, :assignedBy, NULL)
            ON CONFLICT (asset_id, asset_type, om_user_id, role) DO UPDATE
              SET origin             = 'RDM',
                  pinned_local       = EXCLUDED.pinned_local,
                  assigned_by_user_id = EXCLUDED.assigned_by_user_id,
                  superseded_at      = NULL,
                  assigned_at        = now()
            """)
    int assignLocal(
            @Bind("id") UUID id,
            @Bind("assetId") UUID assetId,
            @Bind("assetType") String assetType,
            @Bind("omUserId") UUID omUserId,
            @Bind("role") String role,
            @Bind("pinnedLocal") boolean pinnedLocal,
            @Bind("assignedBy") UUID assignedBy);

    @SqlUpdate(
            "UPDATE ownership.rdm_asset_ownership SET pinned_local = :pinned WHERE id = :id")
    int setPinned(@Bind("id") UUID id, @Bind("pinned") boolean pinned);

    /** Soft-delete: помечаем superseded_at, физически не удаляем. */
    @SqlUpdate(
            "UPDATE ownership.rdm_asset_ownership SET superseded_at = now()"
                    + " WHERE id = :id AND superseded_at IS NULL")
    int softDelete(@Bind("id") UUID id);

    record OwnershipRow(
            UUID id,
            UUID assetId,
            String assetType,
            UUID omUserId,
            String role,
            boolean isProvisional,
            Instant assignedAt,
            UUID assignedBy,
            String sourceEventId,
            String origin,
            boolean pinnedLocal,
            UUID assignedByUserId,
            Instant supersededAt) {}
}
