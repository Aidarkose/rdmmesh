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
 * DAO для admin-операций на {@code catalog.domain}. Существует параллельно с
 * {@code catalog.internal.dao.DomainDao}: тот не знает о master/local_overrides/external_refs
 * (V012). Admin module — единственное место, которое работает с этими полями напрямую,
 * чтобы webhook-receiver (E7) не нужно было трогать в этой раздаче.
 */
public interface AdminDomainDao {

    String COLUMNS =
            "id, om_domain_id, name, display_name, description, label_ru, label_en, tags,"
                    + " master, local_overrides::text AS local_overrides_json,"
                    + " external_refs::text AS external_refs_json,"
                    + " last_om_sync_at, deleted_in_om_at, created_at, updated_at, deleted_at";

    @SqlQuery("SELECT " + COLUMNS + " FROM catalog.domain ORDER BY name")
    @RegisterConstructorMapper(AdminDomainRow.class)
    List<AdminDomainRow> findAll();

    @SqlQuery("SELECT " + COLUMNS + " FROM catalog.domain WHERE id = :id")
    @RegisterConstructorMapper(AdminDomainRow.class)
    Optional<AdminDomainRow> findById(@Bind("id") UUID id);

    @SqlQuery("SELECT " + COLUMNS + " FROM catalog.domain WHERE name = :name")
    @RegisterConstructorMapper(AdminDomainRow.class)
    Optional<AdminDomainRow> findByName(@Bind("name") String name);

    @SqlQuery("SELECT " + COLUMNS + " FROM catalog.domain WHERE om_domain_id = :omId")
    @RegisterConstructorMapper(AdminDomainRow.class)
    Optional<AdminDomainRow> findByOmId(@Bind("omId") UUID omId);

    /**
     * Создание RDM-локального либо LINKED domain'а.
     * При {@code omDomainId=null} → master='RDM'; при не-null → master='LINKED'.
     * CHECK {@code domain_master_id_consistency} (V012) гарантирует инвариант.
     */
    @SqlUpdate(
            """
            INSERT INTO catalog.domain (id, om_domain_id, name, display_name, description,
                                        label_ru, label_en, tags, master)
            VALUES (:id, :omId, :name, :displayName, :description,
                    :labelRu, :labelEn, :tags, :master)
            """)
    int insert(
            @Bind("id") UUID id,
            @Bind("omId") UUID omDomainId,
            @Bind("name") String name,
            @Bind("displayName") String displayName,
            @Bind("description") String description,
            @Bind("labelRu") String labelRu,
            @Bind("labelEn") String labelEn,
            @Bind("tags") String[] tags,
            @Bind("master") String master);

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

    @SqlUpdate(
            """
            UPDATE catalog.domain
               SET name = :name, updated_at = now()
             WHERE id = :id
            """)
    int rename(@Bind("id") UUID id, @Bind("name") String name);

    @SqlUpdate("DELETE FROM catalog.domain WHERE id = :id")
    int hardDelete(@Bind("id") UUID id);

    /** RDM → LINKED: проставить om_domain_id и поменять master одним UPDATE. */
    @SqlUpdate(
            """
            UPDATE catalog.domain
               SET om_domain_id = :omId,
                   master       = 'LINKED',
                   updated_at   = now()
             WHERE id = :id
               AND master = 'RDM'
            """)
    int linkToOm(@Bind("id") UUID id, @Bind("omId") UUID omId);

    /** LINKED → RDM: очистить om_domain_id, схранить в external_refs.former_om. */
    @SqlUpdate(
            """
            UPDATE catalog.domain
               SET external_refs = jsonb_set(external_refs, '{former_om}', to_jsonb(om_domain_id::text)),
                   om_domain_id  = NULL,
                   master        = 'RDM',
                   updated_at    = now()
             WHERE id = :id
               AND master = 'LINKED'
            """)
    int unlinkFromOm(@Bind("id") UUID id);

    /** Активные (не soft-deleted) code_set'ы — для отображения в UI. */
    @SqlQuery(
            "SELECT count(*) FROM catalog.code_set WHERE domain_id = :id AND deleted_at IS NULL")
    long countActiveCodeSets(@Bind("id") UUID id);

    /**
     * ВСЕ code_set'ы домена, включая soft-deleted — guard на DELETE домена.
     * FK {@code code_set.domain_id ... ON DELETE RESTRICT} блокирует hard-delete
     * домена даже при soft-deleted (deleted_at) справочниках: строка-то жива.
     * Поэтому считаем все, иначе DELETE упадёт FK-violation'ом (500 вместо 409).
     */
    @SqlQuery("SELECT count(*) FROM catalog.code_set WHERE domain_id = :id")
    long countAllCodeSets(@Bind("id") UUID id);

    record AdminDomainRow(
            UUID id,
            UUID omDomainId,
            String name,
            String displayName,
            String description,
            String labelRu,
            String labelEn,
            String[] tags,
            String master,
            String localOverridesJson,
            String externalRefsJson,
            Instant lastOmSyncAt,
            Instant deletedInOmAt,
            Instant createdAt,
            Instant updatedAt,
            Instant deletedAt) {}
}
