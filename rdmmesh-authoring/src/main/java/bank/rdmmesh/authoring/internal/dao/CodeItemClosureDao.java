package bank.rdmmesh.authoring.internal.dao;

import java.util.UUID;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO для closure-table иерархий ({@code authoring.code_item_closure}).
 *
 * <p><b>E13 round 2 — closure обслуживается триггерами</b> (миграция V022):
 * INSERT/DELETE/UPDATE-of-parent_key на {@code authoring.code_item} автоматически
 * обновляют closure incremental'но. {@link #rebuild(java.util.UUID)} остаётся как
 * <i>diagnostic / disaster-recovery API</i> — не вызывается в обычной работе
 * {@code AuthoringService}.
 *
 * <p>Closure — INTRA_CODESET only (parent_key). CROSS_CODESET ссылки (parent_ref) живут
 * вне таблицы и резолвятся отдельным запросом.
 */
public interface CodeItemClosureDao {

    @SqlUpdate("DELETE FROM authoring.code_item_closure WHERE version_id = :versionId")
    int deleteAllForVersion(@Bind("versionId") UUID versionId);

    /**
     * Пересоздаёт closure для версии «с нуля» через рекурсивный CTE. Self-reflexive
     * (depth=0) пары добавляются всегда; depth>0 строится через walk вверх по
     * parent_key.
     *
     * <p>Если в данных есть цикл — рекурсия упирается в лимит ({@code WITH RECURSIVE}
     * без UNION ALL не зациклится, а UNION ALL естественно обрубается на дубликате
     * через {@code ON CONFLICT DO NOTHING} в основном INSERT'е, что фактически работает
     * как «пресекаем повтор посещения»).
     */
    @SqlUpdate(
            """
            INSERT INTO authoring.code_item_closure (version_id, ancestor_key, descendant_key, depth)
            WITH RECURSIVE walk AS (
                -- Self-reflexive: каждая нода — предок самой себя на depth=0.
                SELECT version_id, key_parts AS ancestor_key, key_parts AS descendant_key, 0 AS depth
                  FROM authoring.code_item
                 WHERE version_id = :versionId
                UNION ALL
                -- Шаг вверх: ancestor_key текущего ancestor становится новым ancestor.
                SELECT w.version_id, p.key_parts, w.descendant_key, w.depth + 1
                  FROM walk w
                  JOIN authoring.code_item child
                    ON child.version_id = w.version_id
                   AND child.key_parts  = w.ancestor_key
                  JOIN authoring.code_item p
                    ON p.version_id = w.version_id
                   AND p.key_parts  = child.parent_key
                 WHERE child.parent_key IS NOT NULL
                   AND w.depth < 32
            )
            SELECT DISTINCT version_id, ancestor_key, descendant_key, depth FROM walk
            ON CONFLICT (version_id, ancestor_key, descendant_key) DO NOTHING
            """)
    int rebuild(@Bind("versionId") UUID versionId);

    @SqlQuery("SELECT count(*) FROM authoring.code_item_closure WHERE version_id = :versionId")
    int countForVersion(@Bind("versionId") UUID versionId);
}
