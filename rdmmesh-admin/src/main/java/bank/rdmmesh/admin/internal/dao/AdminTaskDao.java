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
 * DAO для {@code admin.resolution_task} (V100). Сейчас READ-онли + resolve — генерация
 * task'ов задача E18.3 (webhook receiver upgrade), её делаем отдельным PR'ом.
 */
public interface AdminTaskDao {

    String COLUMNS =
            "id, task_type, source_event_id, related_domain_id,"
                    + " payload::text AS payload_json, status, resolution_action,"
                    + " resolved_by, resolved_at, notes, created_at";

    @SqlQuery(
            "SELECT " + COLUMNS + " FROM admin.resolution_task"
                    + " WHERE status = 'PENDING' ORDER BY created_at DESC")
    @RegisterConstructorMapper(TaskRow.class)
    List<TaskRow> findPending();

    @SqlQuery("SELECT " + COLUMNS + " FROM admin.resolution_task WHERE id = :id")
    @RegisterConstructorMapper(TaskRow.class)
    Optional<TaskRow> findById(@Bind("id") UUID id);

    /**
     * Атомарный резолв с SKIP LOCKED — защита от двойного резолва, если несколько
     * админ-сессий пытаются разрулить одну задачу одновременно.
     */
    @SqlUpdate(
            """
            UPDATE admin.resolution_task
               SET status            = 'RESOLVED',
                   resolution_action = :action,
                   resolved_by       = :resolvedBy,
                   resolved_at       = now(),
                   notes             = :notes
             WHERE id = :id AND status = 'PENDING'
            """)
    int resolve(
            @Bind("id") UUID id,
            @Bind("action") String action,
            @Bind("resolvedBy") UUID resolvedBy,
            @Bind("notes") String notes);

    record TaskRow(
            UUID id,
            String taskType,
            String sourceEventId,
            UUID relatedDomainId,
            String payloadJson,
            String status,
            String resolutionAction,
            UUID resolvedBy,
            Instant resolvedAt,
            String notes,
            Instant createdAt) {}
}
