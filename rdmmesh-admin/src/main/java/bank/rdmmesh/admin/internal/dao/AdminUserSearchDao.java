package bank.rdmmesh.admin.internal.dao;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

/**
 * Поиск пользователей в локальной mapping-таблице {@code identity.rdm_user_mapping}
 * (E2). Видны только те, кто хоть раз логинился в RDM. Источник {@code source=om} —
 * cross-system lookup в OM REST API — пока не реализован (нужен после подтверждения
 * OM URL и bot-токена, см. E18 §7 open questions).
 */
public interface AdminUserSearchDao {

    @SqlQuery(
            "SELECT om_user_id, keycloak_sub, username, email, display_name, last_seen_at"
                    + "  FROM identity.rdm_user_mapping"
                    + " WHERE username     ILIKE :pattern"
                    + "    OR display_name ILIKE :pattern"
                    + "    OR email        ILIKE :pattern"
                    + " ORDER BY username"
                    + " LIMIT :limit")
    @RegisterConstructorMapper(UserRow.class)
    List<UserRow> search(@Bind("pattern") String pattern, @Bind("limit") int limit);

    record UserRow(
            UUID omUserId,
            UUID keycloakSub,
            String username,
            String email,
            String displayName,
            Instant lastSeenAt) {}
}
