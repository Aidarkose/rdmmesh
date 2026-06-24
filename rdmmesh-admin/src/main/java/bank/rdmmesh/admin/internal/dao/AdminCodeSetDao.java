package bank.rdmmesh.admin.internal.dao;

import java.util.UUID;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO для admin-операций над {@code catalog.code_set} — rename с aliases (V014) и
 * soft-delete. Метаданные patch'ит существующий {@code CodeSetResource} (catalog).
 */
public interface AdminCodeSetDao {

    /**
     * Rename с опциональным сохранением старого имени в JSONB-массиве aliases (V014).
     * Старое имя пушится в начало массива (newest-first). Дубликаты при многократном
     * rename'е игнорируются на UI-уровне (admin сам решает чистить).
     */
    @SqlUpdate(
            """
            UPDATE catalog.code_set
               SET name       = :newName,
                   aliases    = CASE WHEN :keepAlias
                                     THEN jsonb_insert(aliases, '{0}', to_jsonb(name))
                                     ELSE aliases
                                END,
                   updated_at = now()
             WHERE id = :id
               AND deleted_at IS NULL
            """)
    int rename(
            @Bind("id") UUID id,
            @Bind("newName") String newName,
            @Bind("keepAlias") boolean keepAlias);

    @SqlUpdate(
            "UPDATE catalog.code_set SET deleted_at = now(), updated_at = now()"
                    + " WHERE id = :id AND deleted_at IS NULL")
    int softDelete(@Bind("id") UUID id);

    /** Guard: запретить delete если есть PUBLISHED версии (regulator-friendly). */
    @SqlQuery(
            """
            SELECT count(*) FROM authoring.code_set_version
             WHERE codeset_id = :id AND status = 'PUBLISHED'
            """)
    long countPublishedVersions(@Bind("id") UUID id);
}
