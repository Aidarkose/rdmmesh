package bank.rdmmesh.audit.internal.dao;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO для {@code audit.archive_manifest} (V074, E14 round 10). Только
 * INSERT/SELECT — манифест immutable (REVOKE UPDATE/DELETE/TRUNCATE на роль
 * rdmmesh_app, как audit_log). Одна финальная строка на сегмент: повторная
 * заливка того же сегмента → {@code ON CONFLICT (segment_label) DO NOTHING}.
 */
public interface ArchiveManifestDao {

    @SqlUpdate("""
            INSERT INTO audit.archive_manifest
                (segment_label, from_id, to_id, row_count, content_sha256,
                 bucket, object_key, etag, size_bytes, retention_applied,
                 retain_until, archived_by)
            VALUES
                (:segmentLabel, :fromId, :toId, :rowCount, :contentSha256,
                 :bucket, :objectKey, :etag, :sizeBytes, :retentionApplied,
                 :retainUntil, :archivedBy)
            ON CONFLICT (segment_label) DO NOTHING
            """)
    int insert(
            @Bind("segmentLabel") String segmentLabel,
            @Bind("fromId") long fromId,
            @Bind("toId") long toId,
            @Bind("rowCount") long rowCount,
            @Bind("contentSha256") String contentSha256,
            @Bind("bucket") String bucket,
            @Bind("objectKey") String objectKey,
            @Bind("etag") String etag,
            @Bind("sizeBytes") long sizeBytes,
            @Bind("retentionApplied") boolean retentionApplied,
            @Bind("retainUntil") OffsetDateTime retainUntil,
            @Bind("archivedBy") UUID archivedBy);

    @SqlQuery("""
            SELECT segment_label, from_id, to_id, row_count, content_sha256,
                   bucket, object_key, etag, size_bytes, retention_applied,
                   retain_until, archived_by, archived_at
              FROM audit.archive_manifest
             WHERE segment_label = :segmentLabel
            """)
    @RegisterConstructorMapper(ManifestRow.class)
    Optional<ManifestRow> findBySegment(@Bind("segmentLabel") String segmentLabel);

    record ManifestRow(
            @ColumnName("segment_label") String segmentLabel,
            @ColumnName("from_id") long fromId,
            @ColumnName("to_id") long toId,
            @ColumnName("row_count") long rowCount,
            @ColumnName("content_sha256") String contentSha256,
            String bucket,
            @ColumnName("object_key") String objectKey,
            String etag,
            @ColumnName("size_bytes") Long sizeBytes,
            @ColumnName("retention_applied") boolean retentionApplied,
            @ColumnName("retain_until") OffsetDateTime retainUntil,
            @ColumnName("archived_by") UUID archivedBy,
            @ColumnName("archived_at") OffsetDateTime archivedAt) {}
}
