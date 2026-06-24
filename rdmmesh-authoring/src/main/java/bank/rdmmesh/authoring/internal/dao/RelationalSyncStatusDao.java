package bank.rdmmesh.authoring.internal.dao;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * Статус синхронизации rd_data после публикации версии
 * ({@code authoring.relational_sync_status}, V026, Stage 7 B+A).
 *
 * <p>{@code state}: {@code OK} (пересобран), {@code STALE} (публикация прошла, а
 * пост-коммитная пересборка упала), {@code BLOCKED} (пред-проверка отклонила публикацию).
 */
public interface RelationalSyncStatusDao {

    @SqlUpdate(
            """
            INSERT INTO authoring.relational_sync_status
                (version_id, codeset_id, state, reason, updated_at)
            VALUES (:versionId, :codesetId, :state, :reason, now())
            ON CONFLICT (version_id) DO UPDATE
               SET codeset_id = EXCLUDED.codeset_id,
                   state      = EXCLUDED.state,
                   reason     = EXCLUDED.reason,
                   updated_at = now()
            """)
    void upsert(
            @Bind("versionId") UUID versionId,
            @Bind("codesetId") UUID codesetId,
            @Bind("state") String state,
            @Bind("reason") String reason);

    @SqlQuery(
            "SELECT version_id, codeset_id, state, reason, updated_at"
                    + " FROM authoring.relational_sync_status WHERE version_id = :versionId")
    @RegisterConstructorMapper(SyncStatusRow.class)
    Optional<SyncStatusRow> find(@Bind("versionId") UUID versionId);

    record SyncStatusRow(
            UUID versionId, UUID codesetId, String state, String reason, Instant updatedAt) {}
}
