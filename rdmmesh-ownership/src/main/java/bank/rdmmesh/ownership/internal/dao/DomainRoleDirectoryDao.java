package bank.rdmmesh.ownership.internal.dao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO для {@code ownership.domain_role_directory} (справочник ролей домена,
 * BR-21/BR-22, handoff E17).
 *
 * <p>Обновление — полная замена: {@link #truncate()} + построчный
 * {@link #insertResolvingDomain} в одной tx (см.
 * {@code PostgresApproverDirectoryPort.reload}). Дельта-семантики тут нет
 * намеренно — снапшот целиком приходит от мастер-системы.
 */
public interface DomainRoleDirectoryDao {

    @SqlUpdate("TRUNCATE ownership.domain_role_directory")
    void truncate();

    /**
     * Вставка строки с резолвом {@code om_domain_id → catalog.domain.id}.
     * Если домен с таким {@code om_domain_id} не найден — INSERT ... SELECT
     * не вставит ничего (0 rows), entry молча пропускается.
     */
    @SqlUpdate(
            """
            INSERT INTO ownership.domain_role_directory
                (domain_id, role, om_user_id, username, display_name, source)
            SELECT d.id, :role, :omUserId, :username, :displayName, :source
              FROM catalog.domain d
             WHERE d.om_domain_id = :omDomainId
            ON CONFLICT (domain_id, role, om_user_id) DO UPDATE
               SET username     = EXCLUDED.username,
                   display_name = EXCLUDED.display_name,
                   source       = EXCLUDED.source,
                   loaded_at    = now()
            """)
    int insertResolvingDomain(
            @Bind("omDomainId") UUID omDomainId,
            @Bind("role") String role,
            @Bind("omUserId") UUID omUserId,
            @Bind("username") String username,
            @Bind("displayName") String displayName,
            @Bind("source") String source);

    @SqlQuery(
            "SELECT 1 FROM ownership.domain_role_directory"
                    + " WHERE domain_id = :domainId AND role = :role"
                    + "   AND om_user_id = :omUserId LIMIT 1")
    Optional<Integer> exists(
            @Bind("domainId") UUID domainId,
            @Bind("role") String role,
            @Bind("omUserId") UUID omUserId);

    /**
     * Адресная вставка по {@code domain_id} (без резолва om_domain_id) — для
     * локальных доменов. Upsert по уникальному ключу (domain_id, role, om_user_id).
     */
    @SqlUpdate(
            """
            INSERT INTO ownership.domain_role_directory
                (domain_id, role, om_user_id, username, display_name, source)
            VALUES (:domainId, :role, :omUserId, :username, :displayName, :source)
            ON CONFLICT (domain_id, role, om_user_id) DO UPDATE
               SET username     = EXCLUDED.username,
                   display_name = EXCLUDED.display_name,
                   source       = EXCLUDED.source,
                   loaded_at    = now()
            """)
    int insertByDomainId(
            @Bind("domainId") UUID domainId,
            @Bind("role") String role,
            @Bind("omUserId") UUID omUserId,
            @Bind("username") String username,
            @Bind("displayName") String displayName,
            @Bind("source") String source);

    @SqlUpdate(
            "DELETE FROM ownership.domain_role_directory"
                    + " WHERE domain_id = :domainId AND role = :role"
                    + "   AND om_user_id = :omUserId")
    int deleteEntry(
            @Bind("domainId") UUID domainId,
            @Bind("role") String role,
            @Bind("omUserId") UUID omUserId);

    @SqlQuery(
            "SELECT om_user_id, username, display_name, role"
                    + " FROM ownership.domain_role_directory"
                    + " WHERE domain_id = :domainId"
                    + "   AND (:role IS NULL OR role = :role)"
                    + " ORDER BY role, username")
    @RegisterConstructorMapper(ApproverRow.class)
    List<ApproverRow> approversOf(
            @Bind("domainId") UUID domainId,
            @Bind("role") String role);

    /**
     * Резолв роли домена с подъёмом по иерархии (Phase 3). Идём от {@code :domainId}
     * вверх по предкам ({@code catalog.domain.parent_om_domain_id}) и возвращаем
     * <b>ближайшего</b> держателя роли {@code :role}. Используется для доменного
     * fallback владельца: per-asset owner → domain owner → ancestor domain owner.
     */
    @SqlQuery(
            """
            WITH RECURSIVE chain AS (
                SELECT id, om_domain_id, parent_om_domain_id, 0 AS depth
                  FROM catalog.domain
                 WHERE id = :domainId AND deleted_at IS NULL
                UNION ALL
                SELECT p.id, p.om_domain_id, p.parent_om_domain_id, c.depth + 1
                  FROM catalog.domain p
                  JOIN chain c ON p.om_domain_id = c.parent_om_domain_id
                 WHERE p.deleted_at IS NULL
            )
            SELECT dr.om_user_id, dr.username, dr.display_name, dr.role
              FROM chain c
              JOIN ownership.domain_role_directory dr ON dr.domain_id = c.id
             WHERE dr.role = :role
             ORDER BY c.depth, dr.username
             LIMIT 1
            """)
    @RegisterConstructorMapper(ApproverRow.class)
    Optional<ApproverRow> resolveWithFallback(
            @Bind("domainId") UUID domainId,
            @Bind("role") String role);

    /**
     * Авторизован ли {@code :omUserId} как держатель роли {@code :role} для домена
     * {@code :domainId} ИЛИ любого его предка (Phase 3: owner аппрувит справочники
     * своего домена и доменов ниже по иерархии — codeset в домене X разрешён owner'у
     * домена X или любого предка X). Возвращает 1, если найдено.
     */
    @SqlQuery(
            """
            WITH RECURSIVE chain AS (
                SELECT id, om_domain_id, parent_om_domain_id
                  FROM catalog.domain
                 WHERE id = :domainId AND deleted_at IS NULL
                UNION ALL
                SELECT p.id, p.om_domain_id, p.parent_om_domain_id
                  FROM catalog.domain p
                  JOIN chain c ON p.om_domain_id = c.parent_om_domain_id
                 WHERE p.deleted_at IS NULL
            )
            SELECT 1
              FROM chain c
              JOIN ownership.domain_role_directory dr ON dr.domain_id = c.id
             WHERE dr.role = :role AND dr.om_user_id = :omUserId
             LIMIT 1
            """)
    Optional<Integer> isAuthorizedInSubtree(
            @Bind("domainId") UUID domainId,
            @Bind("role") String role,
            @Bind("omUserId") UUID omUserId);

    record ApproverRow(UUID omUserId, String username, String displayName, String role) {}
}
