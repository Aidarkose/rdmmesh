package bank.rdmmesh.workflow.internal.dao;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO для append-only журнала {@code workflow.workflow_transition}. Запись делает
 * {@code WorkflowService} в одной транзакции с CAS статуса (через {@link
 * bank.rdmmesh.api.port.VersionLifecyclePort}). Прямых UPDATE/DELETE здесь нет —
 * append-only по семантике.
 */
public interface WorkflowTransitionDao {

    String COLUMNS =
            "id, version_id, codeset_id, domain_id, from_status, to_status,"
                    + " action, actor, comment, occurred_at";

    @SqlUpdate(
            """
            INSERT INTO workflow.workflow_transition
                (id, version_id, codeset_id, domain_id, from_status, to_status,
                 action, actor, comment)
            VALUES
                (:id, :versionId, :codesetId, :domainId, :fromStatus, :toStatus,
                 :action, :actor, :comment)
            """)
    int insert(
            @Bind("id") UUID id,
            @Bind("versionId") UUID versionId,
            @Bind("codesetId") UUID codesetId,
            @Bind("domainId") UUID domainId,
            @Bind("fromStatus") String fromStatus,
            @Bind("toStatus") String toStatus,
            @Bind("action") String action,
            @Bind("actor") UUID actor,
            @Bind("comment") String comment);

    @SqlQuery("SELECT " + COLUMNS
            + " FROM workflow.workflow_transition"
            + " WHERE version_id = :versionId"
            + " ORDER BY occurred_at ASC, id ASC")
    @RegisterConstructorMapper(TransitionRow.class)
    List<TransitionRow> findByVersion(@Bind("versionId") UUID versionId);

    record TransitionRow(
            UUID id,
            UUID versionId,
            UUID codesetId,
            UUID domainId,
            String fromStatus,
            String toStatus,
            String action,
            UUID actor,
            String comment,
            Instant occurredAt) {}
}
