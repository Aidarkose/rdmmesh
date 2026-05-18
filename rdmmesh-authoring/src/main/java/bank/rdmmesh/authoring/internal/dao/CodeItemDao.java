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
 * DAO для {@code authoring.code_item} — записей справочника внутри версии.
 *
 * <p>Конвенция: все JSONB-поля передаются и читаются как сырые JSON-тексты ({@code String}).
 * Конкретные сериализаторы/десериализаторы живут в service-слое — здесь чистый CRUD.
 *
 * <p>Optimistic locking — через {@code row_version}: service сравнивает значение,
 * присланное клиентом, с текущим в DB и инкрементирует на UPDATE. Конфликт детектируется
 * по 0 affected rows (ниже см. {@link #updateInDraft}).
 *
 * <p>Все SELECT'ы возвращают {@code key_parts::text} — JDBI не умеет отдавать JSONB как
 * Java-массив без extra-конвертеров, а сырая строка устраивает service'ный десериализатор.
 */
public interface CodeItemDao {

    String COLUMNS =
            "id, version_id, key_parts::text AS key_parts_json,"
                    + " parent_key::text AS parent_key_json,"
                    + " parent_ref::text AS parent_ref_json,"
                    + " label_ru, label_en, description_ru, description_en,"
                    + " attributes::text AS attributes_json, order_index, status,"
                    + " effective_from, effective_to, system_from, system_to, row_version";

    @SqlQuery("SELECT " + COLUMNS + " FROM authoring.code_item WHERE id = :id")
    @RegisterConstructorMapper(ItemRow.class)
    Optional<ItemRow> findById(@Bind("id") UUID id);

    @SqlQuery("SELECT " + COLUMNS + " FROM authoring.code_item"
            + " WHERE version_id = :versionId AND key_parts = CAST(:keyPartsJson AS jsonb)")
    @RegisterConstructorMapper(ItemRow.class)
    Optional<ItemRow> findByKey(
            @Bind("versionId") UUID versionId,
            @Bind("keyPartsJson") String keyPartsJson);

    @SqlQuery("SELECT " + COLUMNS + " FROM authoring.code_item"
            + " WHERE version_id = :versionId"
            + " ORDER BY order_index, key_parts::text"
            + " OFFSET :offset LIMIT :limit")
    @RegisterConstructorMapper(ItemRow.class)
    List<ItemRow> page(
            @Bind("versionId") UUID versionId,
            @Bind("offset") int offset,
            @Bind("limit") int limit);

    @SqlQuery("SELECT count(*) FROM authoring.code_item WHERE version_id = :versionId")
    int countByVersion(@Bind("versionId") UUID versionId);

    @SqlQuery("SELECT " + COLUMNS + " FROM authoring.code_item WHERE version_id = :versionId"
            + " ORDER BY key_parts::text")
    @RegisterConstructorMapper(ItemRow.class)
    List<ItemRow> findAll(@Bind("versionId") UUID versionId);

    /**
     * Pure INSERT в DRAFT-версию. Падает с UNIQUE-violation, если key_parts уже есть.
     * Service в случае bulk-операций берёт UPSERT-вариант ниже.
     */
    @SqlUpdate(
            """
            INSERT INTO authoring.code_item
                (id, version_id, key_parts, parent_key, parent_ref,
                 label_ru, label_en, description_ru, description_en,
                 attributes, order_index, status, effective_from, effective_to)
            VALUES
                (:id, :versionId,
                 CAST(:keyPartsJson AS jsonb),
                 CAST(:parentKeyJson AS jsonb),
                 CAST(:parentRefJson AS jsonb),
                 :labelRu, :labelEn, :descriptionRu, :descriptionEn,
                 CAST(:attributesJson AS jsonb), COALESCE(:orderIndex, 0),
                 COALESCE(:status, 'ACTIVE'), :effectiveFrom, :effectiveTo)
            """)
    int insert(
            @Bind("id") UUID id,
            @Bind("versionId") UUID versionId,
            @Bind("keyPartsJson") String keyPartsJson,
            @Bind("parentKeyJson") String parentKeyJson,
            @Bind("parentRefJson") String parentRefJson,
            @Bind("labelRu") String labelRu,
            @Bind("labelEn") String labelEn,
            @Bind("descriptionRu") String descriptionRu,
            @Bind("descriptionEn") String descriptionEn,
            @Bind("attributesJson") String attributesJson,
            @Bind("orderIndex") Integer orderIndex,
            @Bind("status") String status,
            @Bind("effectiveFrom") LocalDate effectiveFrom,
            @Bind("effectiveTo") LocalDate effectiveTo);

    /**
     * Optimistic-locked UPDATE: совпадает только если {@code row_version = :expectedRowVersion}
     * И version всё ещё DRAFT. Возвращает 0 при конфликте — service интерпретирует это
     * как 409 Conflict.
     */
    @SqlUpdate(
            """
            UPDATE authoring.code_item
               SET parent_key       = CAST(:parentKeyJson AS jsonb),
                   parent_ref       = CAST(:parentRefJson AS jsonb),
                   label_ru         = :labelRu,
                   label_en         = :labelEn,
                   description_ru   = :descriptionRu,
                   description_en   = :descriptionEn,
                   attributes       = CAST(:attributesJson AS jsonb),
                   order_index      = COALESCE(:orderIndex, order_index),
                   status           = COALESCE(:status, status),
                   effective_from   = :effectiveFrom,
                   effective_to     = :effectiveTo,
                   row_version      = row_version + 1
             WHERE id = :id
               AND row_version = :expectedRowVersion
               AND EXISTS (
                       SELECT 1 FROM authoring.code_set_version v
                        WHERE v.id = authoring.code_item.version_id
                          AND v.status = 'DRAFT')
            """)
    int updateInDraft(
            @Bind("id") UUID id,
            @Bind("expectedRowVersion") int expectedRowVersion,
            @Bind("parentKeyJson") String parentKeyJson,
            @Bind("parentRefJson") String parentRefJson,
            @Bind("labelRu") String labelRu,
            @Bind("labelEn") String labelEn,
            @Bind("descriptionRu") String descriptionRu,
            @Bind("descriptionEn") String descriptionEn,
            @Bind("attributesJson") String attributesJson,
            @Bind("orderIndex") Integer orderIndex,
            @Bind("status") String status,
            @Bind("effectiveFrom") LocalDate effectiveFrom,
            @Bind("effectiveTo") LocalDate effectiveTo);

    @SqlUpdate(
            """
            DELETE FROM authoring.code_item
             WHERE id = :id
               AND EXISTS (
                       SELECT 1 FROM authoring.code_set_version v
                        WHERE v.id = authoring.code_item.version_id
                          AND v.status = 'DRAFT')
            """)
    int deleteInDraft(@Bind("id") UUID id);

    @SqlUpdate("DELETE FROM authoring.code_item WHERE version_id = :versionId")
    int deleteAllInVersion(@Bind("versionId") UUID versionId);

    record ItemRow(
            UUID id,
            UUID versionId,
            String keyPartsJson,
            String parentKeyJson,
            String parentRefJson,
            String labelRu,
            String labelEn,
            String descriptionRu,
            String descriptionEn,
            String attributesJson,
            Integer orderIndex,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo,
            Instant systemFrom,
            Instant systemTo,
            Integer rowVersion) {}
}
