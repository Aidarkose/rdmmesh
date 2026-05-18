package bank.rdmmesh.distribution.internal.dao;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/**
 * Read-only DAO для consumer-facing distribution-API. SPEC §3.5: distribution отдаёт
 * только данные через REST/bulk export, никаких записей в БД (ArchUnit gate
 * {@code distribution_does_no_db_writes} — поэтому здесь нет {@code @SqlUpdate}/{@code @SqlBatch}).
 *
 * <p>Хранилище — те же таблицы, что у authoring ({@code authoring.code_set_version} +
 * {@code authoring.code_item}), но distribution смотрит на них только через JOIN'ы
 * с denormalized projection'ом для consumer'а — ему не нужна вся authoring-семантика
 * (drafts, row_version, parent closure'ы).
 */
public interface DistributionDao {

    String VERSION_COLS =
            "v.id, v.codeset_id, v.version, v.status,"
                    + " v.content_hash, v.approval_signature,"
                    + " v.published_at, v.deprecated_at,"
                    + " v.system_from, v.system_to,"
                    + " v.item_count";

    String ITEM_COLS =
            "i.id,"
                    + " i.key_parts::text   AS key_parts_json,"
                    + " i.parent_key::text  AS parent_key_json,"
                    + " i.label_ru, i.label_en,"
                    + " i.description_ru, i.description_en,"
                    + " i.attributes::text  AS attributes_json,"
                    + " i.order_index, i.status,"
                    + " i.effective_from, i.effective_to";

    @SqlQuery(
            "SELECT cs.id AS codeset_id, cs.name AS codeset_name,"
                    + " d.id  AS domain_id,  d.name  AS domain_name,"
                    + " (cs.deleted_at IS NOT NULL) AS is_deleted"
                    + "  FROM catalog.code_set cs"
                    + "  JOIN catalog.domain   d ON d.id = cs.domain_id"
                    + " WHERE d.name = :domainName AND cs.name = :codesetName")
    @RegisterConstructorMapper(CodeSetRef.class)
    Optional<CodeSetRef> findCodeSetRef(
            @Bind("domainName") String domainName,
            @Bind("codesetName") String codesetName);

    @SqlQuery(
            "SELECT " + VERSION_COLS
                    + "  FROM authoring.code_set_version v"
                    + " WHERE v.codeset_id = :codesetId"
                    + "   AND v.status = 'PUBLISHED'"
                    + " ORDER BY v.published_at DESC NULLS LAST"
                    + " LIMIT 1")
    @RegisterConstructorMapper(VersionRef.class)
    Optional<VersionRef> findLatestPublished(@Bind("codesetId") UUID codesetId);

    @SqlQuery(
            "SELECT " + VERSION_COLS
                    + "  FROM authoring.code_set_version v"
                    + " WHERE v.codeset_id = :codesetId"
                    + "   AND v.version    = :version"
                    + "   AND v.status IN ('PUBLISHED', 'DEPRECATED')")
    @RegisterConstructorMapper(VersionRef.class)
    Optional<VersionRef> findVersionBySemver(
            @Bind("codesetId") UUID codesetId, @Bind("version") String version);

    /**
     * Версия, известная системе на момент {@code knowledge_as_of} (system time, SPEC §2.3).
     * Bitemporal-фильтр через GiST-индекс {@code tstzrange(system_from, system_to, '[)')}.
     */
    @SqlQuery(
            "SELECT " + VERSION_COLS
                    + "  FROM authoring.code_set_version v"
                    + " WHERE v.codeset_id = :codesetId"
                    + "   AND v.status IN ('PUBLISHED', 'DEPRECATED')"
                    + "   AND tstzrange(v.system_from, v.system_to, '[)') @> :knowledgeAt::timestamptz"
                    + " ORDER BY v.published_at DESC NULLS LAST"
                    + " LIMIT 1")
    @RegisterConstructorMapper(VersionRef.class)
    Optional<VersionRef> findVersionKnownAt(
            @Bind("codesetId") UUID codesetId,
            @Bind("knowledgeAt") Instant knowledgeAt);

    @SqlQuery(
            "SELECT " + ITEM_COLS
                    + "  FROM authoring.code_item i"
                    + " WHERE i.version_id = :versionId"
                    + " ORDER BY i.key_parts::text, i.order_index"
                    + " OFFSET :offset LIMIT :limit")
    @RegisterConstructorMapper(ItemRow.class)
    List<ItemRow> findItemsPage(
            @Bind("versionId") UUID versionId,
            @Bind("offset") int offset,
            @Bind("limit") int limit);

    /**
     * Bitemporal effective-фильтр по дате {@code asOf}. NULL {@code effective_from} —
     * «открытое слева», NULL {@code effective_to} — «открытое справа».
     */
    @SqlQuery(
            "SELECT " + ITEM_COLS
                    + "  FROM authoring.code_item i"
                    + " WHERE i.version_id = :versionId"
                    + "   AND daterange("
                    + "         coalesce(i.effective_from, '-infinity'::date),"
                    + "         coalesce(i.effective_to,   'infinity'::date),"
                    + "         '[)'"
                    + "       ) @> :asOf::date"
                    + " ORDER BY i.key_parts::text, i.order_index"
                    + " OFFSET :offset LIMIT :limit")
    @RegisterConstructorMapper(ItemRow.class)
    List<ItemRow> findItemsPageEffectiveAt(
            @Bind("versionId") UUID versionId,
            @Bind("asOf") LocalDate asOf,
            @Bind("offset") int offset,
            @Bind("limit") int limit);

    @SqlQuery("SELECT count(*) FROM authoring.code_item WHERE version_id = :versionId")
    int countItems(@Bind("versionId") UUID versionId);

    @SqlQuery(
            "SELECT count(*) FROM authoring.code_item i"
                    + " WHERE i.version_id = :versionId"
                    + "   AND daterange("
                    + "         coalesce(i.effective_from, '-infinity'::date),"
                    + "         coalesce(i.effective_to,   'infinity'::date),"
                    + "         '[)'"
                    + "       ) @> :asOf::date")
    int countItemsEffectiveAt(
            @Bind("versionId") UUID versionId, @Bind("asOf") LocalDate asOf);

    @SqlQuery(
            "SELECT " + ITEM_COLS
                    + "  FROM authoring.code_item i"
                    + " WHERE i.version_id = :versionId"
                    + "   AND i.key_parts  = :keyPartsJson::jsonb")
    @RegisterConstructorMapper(ItemRow.class)
    Optional<ItemRow> lookup(
            @Bind("versionId") UUID versionId,
            @Bind("keyPartsJson") String keyPartsJson);

    record CodeSetRef(
            UUID codesetId, String codesetName,
            UUID domainId,  String domainName,
            boolean isDeleted) {}

    record VersionRef(
            UUID id,
            UUID codesetId,
            String version,
            String status,
            String contentHash,
            String approvalSignature,
            Instant publishedAt,
            Instant deprecatedAt,
            Instant systemFrom,
            Instant systemTo,
            int itemCount) {}

    record ItemRow(
            UUID id,
            String keyPartsJson,
            String parentKeyJson,
            String labelRu,
            String labelEn,
            String descriptionRu,
            String descriptionEn,
            String attributesJson,
            int orderIndex,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {}
}
