package bank.rdmmesh.workflow.internal.dao;

import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO для {@code workflow.version_route} (BR-21, handoff E17): кого Author
 * выбрал согласующими версии при submit'е. Upsert по {@code version_id} —
 * повторный submit (после reject) перезаписывает маршрут.
 */
public interface VersionRouteDao {

    @SqlUpdate(
            """
            INSERT INTO workflow.version_route
                (version_id, domain_id, codeset_id, steward_user_id,
                 owner_user_id, created_by)
            VALUES
                (:versionId, :domainId, :codesetId, :stewardUserId,
                 :ownerUserId, :createdBy)
            ON CONFLICT (version_id) DO UPDATE
               SET domain_id       = EXCLUDED.domain_id,
                   codeset_id      = EXCLUDED.codeset_id,
                   steward_user_id = EXCLUDED.steward_user_id,
                   owner_user_id   = EXCLUDED.owner_user_id,
                   created_by      = EXCLUDED.created_by,
                   created_at      = now()
            """)
    int upsert(
            @Bind("versionId") UUID versionId,
            @Bind("domainId") UUID domainId,
            @Bind("codesetId") UUID codesetId,
            @Bind("stewardUserId") UUID stewardUserId,
            @Bind("ownerUserId") UUID ownerUserId,
            @Bind("createdBy") UUID createdBy);

    @SqlQuery(
            "SELECT version_id, domain_id, codeset_id, steward_user_id,"
                    + " owner_user_id, created_by"
                    + " FROM workflow.version_route WHERE version_id = :versionId")
    @RegisterConstructorMapper(VersionRouteRow.class)
    Optional<VersionRouteRow> findByVersion(@Bind("versionId") UUID versionId);

    record VersionRouteRow(
            UUID versionId,
            UUID domainId,
            UUID codesetId,
            UUID stewardUserId,
            UUID ownerUserId,
            UUID createdBy) {}
}
