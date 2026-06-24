package bank.rdmmesh.catalog.internal.dao;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO для {@code catalog.code_set}. Хранит metadata справочника (без items — те живут в
 * {@code authoring.code_item} и подключаются эпиком E4).
 *
 * <p>Поле {@code key_spec} хранится как JSONB; на этом уровне работаем с raw JSON (String),
 * сериализация через mapper в более высоком слое.
 */
public interface CodeSetDao {

    String COLUMNS =
            "id, domain_id, name, display_name, description,"
                    + " label_ru, label_en, tags, key_spec::text AS key_spec_json,"
                    + " column_refs::text AS column_refs_json,"
                    + " hierarchy_mode, release_channels, schema_version,"
                    + " current_published_version, created_at, created_by, updated_at, deleted_at";

    @SqlQuery("SELECT " + COLUMNS + " FROM catalog.code_set"
            + " WHERE domain_id = :domainId AND deleted_at IS NULL"
            + " ORDER BY name")
    @RegisterConstructorMapper(CodeSetRow.class)
    List<CodeSetRow> findActiveByDomain(@Bind("domainId") UUID domainId);

    @SqlQuery("SELECT " + COLUMNS + " FROM catalog.code_set WHERE id = :id")
    @RegisterConstructorMapper(CodeSetRow.class)
    Optional<CodeSetRow> findById(@Bind("id") UUID id);

    @SqlQuery("SELECT " + COLUMNS
            + " FROM catalog.code_set WHERE domain_id = :domainId AND name = :name")
    @RegisterConstructorMapper(CodeSetRow.class)
    Optional<CodeSetRow> findByDomainAndName(
            @Bind("domainId") UUID domainId, @Bind("name") String name);

    @SqlUpdate(
            """
            INSERT INTO catalog.code_set
                (id, domain_id, name, display_name, description, label_ru, label_en, tags,
                 key_spec, hierarchy_mode, release_channels, schema_version, created_by)
            VALUES
                (:id, :domainId, :name, :displayName, :description, :labelRu, :labelEn, :tags,
                 CAST(:keySpecJson AS jsonb), :hierarchyMode, :releaseChannels, 1, :createdBy)
            """)
    int insert(
            @Bind("id") UUID id,
            @Bind("domainId") UUID domainId,
            @Bind("name") String name,
            @Bind("displayName") String displayName,
            @Bind("description") String description,
            @Bind("labelRu") String labelRu,
            @Bind("labelEn") String labelEn,
            @Bind("tags") String[] tags,
            @Bind("keySpecJson") String keySpecJson,
            @Bind("hierarchyMode") String hierarchyMode,
            @Bind("releaseChannels") String[] releaseChannels,
            @Bind("createdBy") UUID createdBy);

    @SqlUpdate(
            """
            UPDATE catalog.code_set
               SET display_name = COALESCE(:displayName, display_name),
                   description  = COALESCE(:description, description),
                   label_ru     = COALESCE(:labelRu,     label_ru),
                   label_en     = COALESCE(:labelEn,     label_en),
                   tags         = COALESCE(:tags,        tags),
                   updated_at   = now()
             WHERE id = :id AND deleted_at IS NULL
            """)
    int patchMetadata(
            @Bind("id") UUID id,
            @Bind("displayName") String displayName,
            @Bind("description") String description,
            @Bind("labelRu") String labelRu,
            @Bind("labelEn") String labelEn,
            @Bind("tags") String[] tags);

    /** Использует service после publish'а новой schema-revision. */
    @SqlUpdate(
            "UPDATE catalog.code_set SET schema_version = :version, updated_at = now()"
                    + " WHERE id = :id AND deleted_at IS NULL")
    int bumpSchemaVersion(@Bind("id") UUID id, @Bind("version") int version);

    @SqlUpdate(
            "UPDATE catalog.code_set SET deleted_at = now(), updated_at = now()"
                    + " WHERE id = :id AND deleted_at IS NULL")
    int softDelete(@Bind("id") UUID id);

    /**
     * Заменяет весь набор cross-codeset FK-связей справочника. {@code columnRefsJson} — это
     * JSON-массив объектов {@code {from_column, to_codeset_id, to_column, label?}} (см.
     * spec entity/code-set.json#/properties/references). Связи описательны: referential
     * integrity на уровне БД не проверяется (цель может быть в другом домене).
     */
    @SqlUpdate(
            "UPDATE catalog.code_set"
                    + " SET column_refs = CAST(:columnRefsJson AS jsonb), updated_at = now()"
                    + " WHERE id = :id AND deleted_at IS NULL")
    int updateReferences(@Bind("id") UUID id, @Bind("columnRefsJson") String columnRefsJson);

    record CodeSetRow(
            UUID id,
            UUID domainId,
            String name,
            String displayName,
            String description,
            String labelRu,
            String labelEn,
            String[] tags,
            String keySpecJson,
            String columnRefsJson,
            String hierarchyMode,
            String[] releaseChannels,
            Integer schemaVersion,
            String currentPublishedVersion,
            Instant createdAt,
            UUID createdBy,
            Instant updatedAt,
            Instant deletedAt) {}
}
