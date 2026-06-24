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
 * DAO для {@code catalog.code_set_deletion_request} (E22).
 *
 * <p>Список с join'ами (codeset/domain/identity) живёт в этом же DAO —
 * через {@link DetailedRow}, чтобы admin-очередь рисовалась без N+1.
 * Конструктор-маппинг по позициям; держим SELECT-list синхронным с record-полями.
 */
public interface AdminDeletionRequestDao {

    String BASE_COLUMNS =
            "r.id, r.codeset_id, r.requested_by, r.reason, r.status,"
                    + " r.decided_by, r.decision_comment, r.created_at, r.decided_at";

    String DETAILED_COLUMNS =
            BASE_COLUMNS
                    + ", cs.name AS codeset_name, cs.domain_id, d.name AS domain_name,"
                    + " (cs.deleted_at IS NOT NULL) AS codeset_deleted,"
                    + " req_um.username AS requested_by_username,"
                    + " dec_um.username AS decided_by_username,"
                    + " EXISTS (SELECT 1 FROM authoring.code_set_version v"
                    + "         WHERE v.codeset_id = cs.id AND v.status = 'PUBLISHED') AS has_published_versions";

    String DETAILED_FROM =
            " FROM catalog.code_set_deletion_request r"
                    + " JOIN catalog.code_set cs ON cs.id = r.codeset_id"
                    + " JOIN catalog.domain   d  ON d.id  = cs.domain_id"
                    + " LEFT JOIN identity.rdm_user_mapping req_um ON req_um.om_user_id = r.requested_by"
                    + " LEFT JOIN identity.rdm_user_mapping dec_um ON dec_um.om_user_id = r.decided_by";

    @SqlUpdate(
            """
            INSERT INTO catalog.code_set_deletion_request
                (id, codeset_id, requested_by, reason)
            VALUES (:id, :codesetId, :requestedBy, :reason)
            """)
    int insert(
            @Bind("id") UUID id,
            @Bind("codesetId") UUID codesetId,
            @Bind("requestedBy") UUID requestedBy,
            @Bind("reason") String reason);

    @SqlQuery("SELECT " + BASE_COLUMNS + " FROM catalog.code_set_deletion_request r WHERE r.id = :id")
    @RegisterConstructorMapper(BaseRow.class)
    Optional<BaseRow> findById(@Bind("id") UUID id);

    @SqlQuery("SELECT " + DETAILED_COLUMNS + DETAILED_FROM + " WHERE r.id = :id")
    @RegisterConstructorMapper(DetailedRow.class)
    Optional<DetailedRow> findByIdDetailed(@Bind("id") UUID id);

    @SqlQuery(
            "SELECT " + DETAILED_COLUMNS + DETAILED_FROM
                    + " WHERE r.status = :status"
                    + " ORDER BY r.created_at DESC")
    @RegisterConstructorMapper(DetailedRow.class)
    List<DetailedRow> listByStatus(@Bind("status") String status);

    @SqlQuery(
            "SELECT " + DETAILED_COLUMNS + DETAILED_FROM
                    + " WHERE r.requested_by = :userId"
                    + " ORDER BY r.created_at DESC")
    @RegisterConstructorMapper(DetailedRow.class)
    List<DetailedRow> listByRequestedBy(@Bind("userId") UUID userId);

    /**
     * Перевод PENDING → terminal. WHERE-clause страхует от race: если кто-то
     * параллельно уже решил/отменил эту заявку — UPDATE вернёт 0, service
     * интерпретирует это как 409.
     */
    @SqlUpdate(
            """
            UPDATE catalog.code_set_deletion_request
               SET status           = :newStatus,
                   decided_by       = :decidedBy,
                   decision_comment = :decisionComment,
                   decided_at       = now()
             WHERE id = :id
               AND status = 'PENDING'
            """)
    int transitionFromPending(
            @Bind("id") UUID id,
            @Bind("newStatus") String newStatus,
            @Bind("decidedBy") UUID decidedBy,
            @Bind("decisionComment") String decisionComment);

    /** «Голая» строка без join'ов — для state-check'ов внутри Service. */
    record BaseRow(
            UUID id,
            UUID codesetId,
            UUID requestedBy,
            String reason,
            String status,
            UUID decidedBy,
            String decisionComment,
            Instant createdAt,
            Instant decidedAt) {}

    /** Строка с join'ами codeset/domain/identity — для REST-view. */
    record DetailedRow(
            UUID id,
            UUID codesetId,
            UUID requestedBy,
            String reason,
            String status,
            UUID decidedBy,
            String decisionComment,
            Instant createdAt,
            Instant decidedAt,
            String codesetName,
            UUID domainId,
            String domainName,
            boolean codesetDeleted,
            String requestedByUsername,
            String decidedByUsername,
            boolean hasPublishedVersions) {}
}
