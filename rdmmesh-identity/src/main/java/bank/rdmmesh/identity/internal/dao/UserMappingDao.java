package bank.rdmmesh.identity.internal.dao;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO для {@code identity.rdm_user_mapping}. Маппинг пользователей между Keycloak (sub) и
 * OpenMetadata (User.id) — заполняется лениво при первом успешном JWT-флоу (см. SPEC §2.4).
 *
 * <p>Все вызовы — через {@code Jdbi.useExtension(UserMappingDao.class, ...)} либо через
 * {@code Handle.attach(...)}, чтобы транзакции контролировались выше.
 */
public interface UserMappingDao {

    @SqlQuery(
            "SELECT om_user_id, keycloak_sub, username, email, display_name,"
                    + " first_seen_at, last_seen_at"
                    + " FROM identity.rdm_user_mapping WHERE keycloak_sub = :keycloakSub")
    @RegisterConstructorMapper(UserMappingRow.class)
    Optional<UserMappingRow> findByKeycloakSub(@Bind("keycloakSub") UUID keycloakSub);

    @SqlQuery(
            "SELECT om_user_id, keycloak_sub, username, email, display_name,"
                    + " first_seen_at, last_seen_at"
                    + " FROM identity.rdm_user_mapping WHERE om_user_id = :omUserId")
    @RegisterConstructorMapper(UserMappingRow.class)
    Optional<UserMappingRow> findByOmUserId(@Bind("omUserId") UUID omUserId);

    @SqlQuery(
            "SELECT om_user_id, keycloak_sub, username, email, display_name,"
                    + " first_seen_at, last_seen_at"
                    + " FROM identity.rdm_user_mapping WHERE lower(username) = lower(:username)")
    @RegisterConstructorMapper(UserMappingRow.class)
    Optional<UserMappingRow> findByUsername(@Bind("username") String username);

    /**
     * Идемпотентная lazy-вставка маппинга, реконсилируемая по <b>стабильному
     * натуральному ключу — {@code username}</b> (sAMAccountName).
     *
     * <p><b>Почему конфликт по {@code username}, а не по {@code keycloak_sub}.</b>
     * {@code username} идентифицирует пользователя неизменно; {@code keycloak_sub}
     * меняется при ре-провижининге Keycloak (новый realm / пере-импорт из AD), а
     * {@code om_user_id} — при сбросе/переезде OM либо при переходе
     * provisional → real (SPEC §2.4 явно разрешает {@code UPDATE SET om_user_id
     * = real} как reconciliation). Прежняя версия конфликтовала только по
     * {@code keycloak_sub}: при новом {@code sub} с тем же {@code username}
     * INSERT нарушал {@code unique(username)} (и/или PK {@code om_user_id}) →
     * HTTP 500 на первом же запросе после пересборки Keycloak.
     *
     * <p>Теперь конфликт по {@code username} обновляет «подвижные»
     * идентификаторы ({@code om_user_id}, {@code keycloak_sub}) и
     * last_seen/email/display_name, сохраняя {@code first_seen_at}. Внешних FK
     * на {@code om_user_id} нет (проверено) — обновление PK безопасно.
     */
    @SqlUpdate(
            """
            INSERT INTO identity.rdm_user_mapping
                (om_user_id, keycloak_sub, username, email, display_name)
            VALUES (:omUserId, :keycloakSub, :username, :email, :displayName)
            ON CONFLICT (username) DO UPDATE
              SET om_user_id   = EXCLUDED.om_user_id,
                  keycloak_sub = EXCLUDED.keycloak_sub,
                  last_seen_at = now(),
                  email        = COALESCE(EXCLUDED.email, identity.rdm_user_mapping.email),
                  display_name = COALESCE(EXCLUDED.display_name, identity.rdm_user_mapping.display_name)
            """)
    int upsert(
            @Bind("omUserId") UUID omUserId,
            @Bind("keycloakSub") UUID keycloakSub,
            @Bind("username") String username,
            @Bind("email") String email,
            @Bind("displayName") String displayName);

    @SqlUpdate(
            "UPDATE identity.rdm_user_mapping SET last_seen_at = now()"
                    + " WHERE keycloak_sub = :keycloakSub")
    int touchLastSeen(@Bind("keycloakSub") UUID keycloakSub);

    /** Read-projection для row mapper. */
    record UserMappingRow(
            UUID omUserId,
            UUID keycloakSub,
            String username,
            String email,
            String displayName,
            Instant firstSeenAt,
            Instant lastSeenAt) {}
}
