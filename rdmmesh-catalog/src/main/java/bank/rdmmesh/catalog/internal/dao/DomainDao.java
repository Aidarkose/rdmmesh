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
 * DAO для {@code catalog.domain}. В нашей модели Domain — отражение domain'а из
 * OpenMetadata (см. SPEC §2.4); таблица — кэш для join'ов и referential integrity
 * на {@code code_set.domain_id}. Identity flows from OM (UUID — {@code om_domain_id}).
 */
public interface DomainDao {

    String COLUMNS =
            "id, om_domain_id, parent_om_domain_id, name, display_name, description,"
                    + " label_ru, label_en, tags, created_at, updated_at, deleted_at";

    @SqlQuery("SELECT " + COLUMNS + " FROM catalog.domain ORDER BY name")
    @RegisterConstructorMapper(DomainRow.class)
    List<DomainRow> findAll();

    @SqlQuery("SELECT " + COLUMNS + " FROM catalog.domain WHERE id = :id")
    @RegisterConstructorMapper(DomainRow.class)
    Optional<DomainRow> findById(@Bind("id") UUID id);

    @SqlQuery("SELECT " + COLUMNS + " FROM catalog.domain WHERE name = :name")
    @RegisterConstructorMapper(DomainRow.class)
    Optional<DomainRow> findByName(@Bind("name") String name);

    @SqlQuery("SELECT " + COLUMNS + " FROM catalog.domain WHERE om_domain_id = :omDomainId")
    @RegisterConstructorMapper(DomainRow.class)
    Optional<DomainRow> findByOmId(@Bind("omDomainId") UUID omDomainId);

    @SqlUpdate(
            """
            INSERT INTO catalog.domain (id, om_domain_id, name, display_name, description,
                                        label_ru, label_en, tags)
            VALUES (:id, :omDomainId, :name, :displayName, :description,
                    :labelRu, :labelEn, :tags)
            """)
    int insert(
            @Bind("id") UUID id,
            @Bind("omDomainId") UUID omDomainId,
            @Bind("name") String name,
            @Bind("displayName") String displayName,
            @Bind("description") String description,
            @Bind("labelRu") String labelRu,
            @Bind("labelEn") String labelEn,
            @Bind("tags") String[] tags);

    @SqlUpdate(
            """
            UPDATE catalog.domain
               SET display_name = COALESCE(:displayName, display_name),
                   description  = COALESCE(:description, description),
                   label_ru     = COALESCE(:labelRu,     label_ru),
                   label_en     = COALESCE(:labelEn,     label_en),
                   tags         = COALESCE(:tags,        tags),
                   updated_at   = now()
             WHERE id = :id
            """)
    int patch(
            @Bind("id") UUID id,
            @Bind("displayName") String displayName,
            @Bind("description") String description,
            @Bind("labelRu") String labelRu,
            @Bind("labelEn") String labelEn,
            @Bind("tags") String[] tags);

    /**
     * UPSERT mirror'а domain'а из OM webhook'а. Идемпотентно по {@code om_domain_id}.
     * При повторе с новыми значениями полей — UPDATE. Если deleted_at был выставлен
     * (soft-delete), он сбрасывается в NULL — это resurrect (см. SPEC §2.4).
     */
    @SqlUpdate(
            """
            INSERT INTO catalog.domain
                (om_domain_id, parent_om_domain_id, name, display_name, description,
                 label_ru, label_en, tags)
            VALUES
                (:omDomainId, :parentOmDomainId, :name, :displayName, :description,
                 :labelRu, :labelEn, :tags)
            ON CONFLICT (om_domain_id) DO UPDATE
              SET parent_om_domain_id =
                      COALESCE(EXCLUDED.parent_om_domain_id, catalog.domain.parent_om_domain_id),
                  name         = EXCLUDED.name,
                  display_name = EXCLUDED.display_name,
                  description  = EXCLUDED.description,
                  label_ru     = EXCLUDED.label_ru,
                  label_en     = EXCLUDED.label_en,
                  tags         = EXCLUDED.tags,
                  deleted_at   = NULL,
                  updated_at   = now()
            """)
    int upsertByOmId(
            @Bind("omDomainId") UUID omDomainId,
            @Bind("parentOmDomainId") UUID parentOmDomainId,
            @Bind("name") String name,
            @Bind("displayName") String displayName,
            @Bind("description") String description,
            @Bind("labelRu") String labelRu,
            @Bind("labelEn") String labelEn,
            @Bind("tags") String[] tags);

    /**
     * Все {@code om_domain_id} поддерева, укоренённого в {@code :rootOmId} (включая корень).
     * Рекурсивный обход вниз по {@code parent_om_domain_id}; soft-deleted исключены.
     * Phase 3: scope owner'а — аппрув в пределах своего поддерева доменов.
     */
    @SqlQuery(
            """
            WITH RECURSIVE subtree AS (
                SELECT om_domain_id, parent_om_domain_id
                  FROM catalog.domain
                 WHERE om_domain_id = :rootOmId AND deleted_at IS NULL
                UNION ALL
                SELECT d.om_domain_id, d.parent_om_domain_id
                  FROM catalog.domain d
                  JOIN subtree s ON d.parent_om_domain_id = s.om_domain_id
                 WHERE d.deleted_at IS NULL
            )
            SELECT om_domain_id FROM subtree
            """)
    List<UUID> descendantOmIds(@Bind("rootOmId") UUID rootOmId);

    /**
     * Цепочка предков домена {@code :omId} снизу вверх (БЕЗ самого домена), от ближайшего
     * родителя к корню. Phase 3: fallback владельца — domain owner → ancestor domain owner.
     */
    @SqlQuery(
            """
            WITH RECURSIVE ancestors AS (
                SELECT om_domain_id, parent_om_domain_id, 0 AS depth
                  FROM catalog.domain
                 WHERE om_domain_id = :omId
                UNION ALL
                SELECT p.om_domain_id, p.parent_om_domain_id, a.depth + 1
                  FROM catalog.domain p
                  JOIN ancestors a ON p.om_domain_id = a.parent_om_domain_id
                 WHERE p.deleted_at IS NULL
            )
            SELECT om_domain_id FROM ancestors WHERE depth > 0 ORDER BY depth
            """)
    List<UUID> ancestorOmIds(@Bind("omId") UUID omId);

    @SqlUpdate(
            "UPDATE catalog.domain SET deleted_at = now(), updated_at = now()"
                    + " WHERE om_domain_id = :omDomainId AND deleted_at IS NULL")
    int softDeleteByOmId(@Bind("omDomainId") UUID omDomainId);

    /** Snapshot строки domain'а — внутренний транспорт между DAO и mapper'ом. */
    record DomainRow(
            UUID id,
            UUID omDomainId,
            UUID parentOmDomainId,
            String name,
            String displayName,
            String description,
            String labelRu,
            String labelEn,
            String[] tags,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt) {}
}
