package bank.rdmmesh.workflow.internal.dao;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO для материализованной "My Tasks" таблицы {@code workflow.approval_task}.
 * UNIQUE (version_id, required_role) гарантирует, что у одной версии не более одной
 * открытой задачи на роль — повторный submit/approve просто закрывает и пересоздаёт.
 *
 * <p>{@code candidate_users} — массив om_user_id, кому задача видна в "/tasks/my".
 * До эпика E7 (OM ownership webhook) STEWARD'ы в {@code rdm_asset_ownership} ещё не
 * заведены — массив будет пустым, видимость в /tasks/my будет нулевой; пилот вызывает
 * transitions API напрямую, role gate отвязан от candidate_users (см. StateMachine).
 */
public interface ApprovalTaskDao {

    String COLUMNS =
            "id, version_id, codeset_id, domain_id, required_role, candidate_users,"
                    + " created_at, closed_at, closed_by";

    /**
     * Открыть задачу (или переоткрыть, если уже была закрыта). UPSERT по
     * (version_id, required_role): обнуляет {@code closed_at}/{@code closed_by} и
     * обновляет список кандидатов.
     */
    @SqlUpdate(
            """
            INSERT INTO workflow.approval_task
                (version_id, codeset_id, domain_id, required_role, candidate_users)
            VALUES
                (:versionId, :codesetId, :domainId, :requiredRole, :candidates)
            ON CONFLICT (version_id, required_role) DO UPDATE
                SET candidate_users = EXCLUDED.candidate_users,
                    created_at      = now(),
                    closed_at       = NULL,
                    closed_by       = NULL
            """)
    int upsertOpen(
            @Bind("versionId") UUID versionId,
            @Bind("codesetId") UUID codesetId,
            @Bind("domainId") UUID domainId,
            @Bind("requiredRole") String requiredRole,
            @Bind("candidates") UUID[] candidateUsers);

    /** Закрыть задачу указанной роли — без ошибок если открытой задачи и не было. */
    @SqlUpdate(
            """
            UPDATE workflow.approval_task
               SET closed_at = now(), closed_by = :closedBy
             WHERE version_id = :versionId
               AND required_role = :requiredRole
               AND closed_at IS NULL
            """)
    int close(
            @Bind("versionId") UUID versionId,
            @Bind("requiredRole") String requiredRole,
            @Bind("closedBy") UUID closedBy);

    /** Закрыть все открытые задачи версии — на reject и terminal-переходах. */
    @SqlUpdate(
            """
            UPDATE workflow.approval_task
               SET closed_at = now(), closed_by = :closedBy
             WHERE version_id = :versionId
               AND closed_at IS NULL
            """)
    int closeAll(
            @Bind("versionId") UUID versionId,
            @Bind("closedBy") UUID closedBy);

    @SqlQuery("SELECT " + COLUMNS
            + " FROM workflow.approval_task"
            + " WHERE :user = ANY(candidate_users)"
            + "   AND closed_at IS NULL"
            + " ORDER BY created_at DESC")
    @RegisterConstructorMapper(ApprovalTaskRow.class)
    List<ApprovalTaskRow> findOpenByUser(@Bind("user") UUID omUserId);

    @SqlQuery("SELECT " + COLUMNS
            + " FROM workflow.approval_task"
            + " WHERE version_id = :versionId AND required_role = :requiredRole")
    @RegisterConstructorMapper(ApprovalTaskRow.class)
    java.util.Optional<ApprovalTaskRow> findOne(
            @Bind("versionId") UUID versionId,
            @Bind("requiredRole") String requiredRole);

    record ApprovalTaskRow(
            UUID id,
            UUID versionId,
            UUID codesetId,
            UUID domainId,
            String requiredRole,
            UUID[] candidateUsers,
            Instant createdAt,
            Instant closedAt,
            UUID closedBy) {}
}
