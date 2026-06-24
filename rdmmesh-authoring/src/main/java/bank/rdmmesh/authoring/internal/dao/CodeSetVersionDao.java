package bank.rdmmesh.authoring.internal.dao;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO для {@code authoring.code_set_version} — заголовок версии справочника
 * (DRAFT и опубликованные snapshot'ы). Имеет CHECK на статус и UNIQUE по
 * (codeset_id, version) — дубликаты semver внутри CodeSet'а отлавливаются БД.
 *
 * <p>Иммутабельность published-версий выражена в схеме через CHECK на content_hash и
 * grant'ы; в коде — через явные проверки в service-слое и в {@code update*}-методах
 * этого DAO (они отказываются работать с PUBLISHED-версией).
 */
public interface CodeSetVersionDao {

    String COLUMNS =
            "id, codeset_id, version, status, schema_version, release_channel,"
                    + " effective_from, effective_to, system_from, system_to,"
                    + " created_at, created_by, approved_by, published_by, published_at, deprecated_at,"
                    + " content_hash, approval_signature, owner_was_provisional, item_count";

    @SqlQuery("SELECT " + COLUMNS + " FROM authoring.code_set_version WHERE id = :id")
    @RegisterConstructorMapper(VersionRow.class)
    Optional<VersionRow> findById(@Bind("id") UUID id);

    @SqlQuery("SELECT " + COLUMNS
            + " FROM authoring.code_set_version WHERE codeset_id = :codesetId"
            + " ORDER BY created_at DESC")
    @RegisterConstructorMapper(VersionRow.class)
    List<VersionRow> findByCodeset(@Bind("codesetId") UUID codesetId);

    @SqlQuery("SELECT " + COLUMNS
            + " FROM authoring.code_set_version"
            + " WHERE codeset_id = :codesetId AND status = 'PUBLISHED'"
            + " ORDER BY published_at DESC NULLS LAST LIMIT 1")
    @RegisterConstructorMapper(VersionRow.class)
    Optional<VersionRow> findLatestPublished(@Bind("codesetId") UUID codesetId);

    @SqlQuery("SELECT " + COLUMNS
            + " FROM authoring.code_set_version"
            + " WHERE codeset_id = :codesetId AND version = :version")
    @RegisterConstructorMapper(VersionRow.class)
    Optional<VersionRow> findByCodesetAndVersion(
            @Bind("codesetId") UUID codesetId, @Bind("version") String version);

    @SqlUpdate(
            """
            INSERT INTO authoring.code_set_version
                (id, codeset_id, version, status, schema_version, release_channel, created_by)
            VALUES
                (:id, :codesetId, :version, 'DRAFT', :schemaVersion,
                 COALESCE(:releaseChannel, 'PROD'), :createdBy)
            """)
    int insertDraft(
            @Bind("id") UUID id,
            @Bind("codesetId") UUID codesetId,
            @Bind("version") String version,
            @Bind("schemaVersion") int schemaVersion,
            @Bind("releaseChannel") String releaseChannel,
            @Bind("createdBy") UUID createdBy);

    /** Меняет item_count счётчик. Используется service'ом после bulk-операций. */
    @SqlUpdate("UPDATE authoring.code_set_version SET item_count = :count"
            + " WHERE id = :id AND status = 'DRAFT'")
    int setItemCount(@Bind("id") UUID id, @Bind("count") int count);

    @SqlUpdate(
            """
            UPDATE authoring.code_set_version
               SET effective_from = COALESCE(:effectiveFrom, effective_from),
                   effective_to   = COALESCE(:effectiveTo,   effective_to)
             WHERE id = :id AND status = 'DRAFT'
            """)
    int patchEffective(
            @Bind("id") UUID id,
            @Bind("effectiveFrom") LocalDate effectiveFrom,
            @Bind("effectiveTo") LocalDate effectiveTo);

    @SqlUpdate("DELETE FROM authoring.code_set_version"
            + " WHERE id = :id AND status = 'DRAFT'")
    int deleteDraft(@Bind("id") UUID id);

    /**
     * Версии в open-states (не PUBLISHED/DEPRECATED/REJECTED) — используется для
     * at-most-one-DRAFT проверки в {@code AuthoringService.createDraft} и для отчётов.
     */
    @SqlQuery("SELECT " + COLUMNS
            + " FROM authoring.code_set_version"
            + " WHERE codeset_id = :codesetId"
            + "   AND status IN ('DRAFT', 'IN_REVIEW', 'STEWARD_APPROVED', 'OWNER_APPROVED')"
            + " ORDER BY created_at DESC")
    @RegisterConstructorMapper(VersionRow.class)
    List<VersionRow> findOpenVersions(@Bind("codesetId") UUID codesetId);

    /**
     * CAS-переход статуса. Ноль affected rows → текущий статус не совпал
     * (concurrent transition или version404).
     */
    @SqlUpdate("UPDATE authoring.code_set_version"
            + " SET status = :to"
            + " WHERE id = :id AND status = :from")
    int casStatus(
            @Bind("id") UUID id,
            @Bind("from") String fromStatus,
            @Bind("to") String toStatus);

    /** Проставить approved_by при OWNER_APPROVED transition. */
    @SqlUpdate("UPDATE authoring.code_set_version"
            + " SET approved_by = :actor"
            + " WHERE id = :id")
    int setApprover(@Bind("id") UUID id, @Bind("actor") UUID actor);

    /**
     * Пометить версию опубликованной (CAS OWNER_APPROVED → PUBLISHED) с заполнением
     * криптографических полей. {@code content_hash}/{@code approval_signature} проходят
     * CHECK на 64-hex; CHECK на PUBLISHED → NOT NULL также гарантирует invariant.
     */
    @SqlUpdate(
            """
            UPDATE authoring.code_set_version
               SET status              = 'PUBLISHED',
                   content_hash        = :contentHash,
                   approval_signature  = :signature,
                   published_by        = :publishedBy,
                   published_at        = now()
             WHERE id = :id AND status = 'OWNER_APPROVED'
            """)
    int markPublished(
            @Bind("id") UUID id,
            @Bind("contentHash") String contentHash,
            @Bind("signature") String signature,
            @Bind("publishedBy") UUID publishedBy);

    /** Пометить версию устаревшей: status, deprecated_at, system_to. */
    @SqlUpdate(
            """
            UPDATE authoring.code_set_version
               SET status        = 'DEPRECATED',
                   deprecated_at = now(),
                   system_to     = now()
             WHERE id = :id AND status = 'PUBLISHED'
            """)
    int markDeprecated(@Bind("id") UUID id);

    /**
     * Зарегистрировать steward'а — на каждый steward_approve. UNIQUE (version_id, om_user_id)
     * сделает повторный INSERT no-op'ом (через ON CONFLICT).
     */
    @SqlUpdate("INSERT INTO authoring.code_set_version_reviewer (version_id, om_user_id)"
            + " VALUES (:versionId, :reviewer)"
            + " ON CONFLICT (version_id, om_user_id) DO NOTHING")
    int recordReviewer(
            @Bind("versionId") UUID versionId,
            @Bind("reviewer") UUID reviewerOmUserId);

    @SqlQuery("SELECT om_user_id FROM authoring.code_set_version_reviewer"
            + " WHERE version_id = :versionId")
    List<UUID> reviewersOf(@Bind("versionId") UUID versionId);

    record VersionRow(
            UUID id,
            UUID codesetId,
            String version,
            String status,
            Integer schemaVersion,
            String releaseChannel,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Instant systemFrom,
            Instant systemTo,
            Instant createdAt,
            UUID createdBy,
            UUID approvedBy,
            UUID publishedBy,
            Instant publishedAt,
            Instant deprecatedAt,
            String contentHash,
            String approvalSignature,
            Boolean ownerWasProvisional,
            Integer itemCount) {}
}
