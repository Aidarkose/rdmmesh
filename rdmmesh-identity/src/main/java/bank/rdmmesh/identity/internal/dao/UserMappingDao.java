package bank.rdmmesh.identity.internal.dao;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO для {@code identity.rdm_user_mapping}. Маппинг пользователей между AD
 * (objectGUID — claim {@code oid}) и OpenMetadata (User.id).
 *
 * <p><b>Якорь — {@code object_guid}.</b> objectGUID стабилен и идемпотентен на стороне
 * AD; {@code keycloak_sub} волатилен (регенерируется при пере-импорте realm /
 * re-провижининге федерации) и понижен до обычного атрибута. {@code om_user_id}
 * NULLABLE: viewer без DG-роли в OM его не имеет (read-only). Заполняется, когда OM
 * по имени учётки вернул реальный {@code User.id} — один раз, далее замораживается.
 *
 * <p>Все вызовы — через {@code Jdbi.useExtension(UserMappingDao.class, ...)} либо через
 * {@code Handle.attach(...)}, чтобы транзакции контролировались выше.
 */
public interface UserMappingDao {

    String COLUMNS =
            "object_guid, om_user_id, keycloak_sub, username, email, display_name,"
                    + " first_seen_at, last_seen_at";

    @SqlQuery("SELECT " + COLUMNS
            + " FROM identity.rdm_user_mapping WHERE object_guid = :objectGuid")
    @RegisterConstructorMapper(UserMappingRow.class)
    Optional<UserMappingRow> findByObjectGuid(@Bind("objectGuid") UUID objectGuid);

    @SqlQuery("SELECT " + COLUMNS
            + " FROM identity.rdm_user_mapping WHERE keycloak_sub = :keycloakSub")
    @RegisterConstructorMapper(UserMappingRow.class)
    Optional<UserMappingRow> findByKeycloakSub(@Bind("keycloakSub") UUID keycloakSub);

    @SqlQuery("SELECT " + COLUMNS
            + " FROM identity.rdm_user_mapping WHERE om_user_id = :omUserId")
    @RegisterConstructorMapper(UserMappingRow.class)
    Optional<UserMappingRow> findByOmUserId(@Bind("omUserId") UUID omUserId);

    @SqlQuery("SELECT " + COLUMNS
            + " FROM identity.rdm_user_mapping WHERE lower(username) = lower(:username)")
    @RegisterConstructorMapper(UserMappingRow.class)
    Optional<UserMappingRow> findByUsername(@Bind("username") String username);

    /**
     * Идемпотентная lazy-вставка маппинга, реконсилируемая по <b>стабильному
     * натуральному ключу — {@code username}</b> (sAMAccountName).
     *
     * <p>Конфликт по {@code username} обновляет «подвижные» идентификаторы
     * ({@code object_guid}, {@code keycloak_sub}) и last_seen/email/display_name,
     * сохраняя {@code first_seen_at}. {@code om_user_id} обновляется только если
     * новое значение не NULL (не затираем уже привязанный реальный OM-id пустым
     * значением, когда viewer ещё не получил роль) — см. COALESCE.
     */
    @SqlUpdate(
            """
            INSERT INTO identity.rdm_user_mapping
                (object_guid, om_user_id, keycloak_sub, username, email, display_name)
            VALUES (:objectGuid, :omUserId, :keycloakSub, :username, :email, :displayName)
            ON CONFLICT (username) DO UPDATE
              SET object_guid  = EXCLUDED.object_guid,
                  keycloak_sub = EXCLUDED.keycloak_sub,
                  om_user_id   = COALESCE(EXCLUDED.om_user_id, identity.rdm_user_mapping.om_user_id),
                  last_seen_at = now(),
                  email        = COALESCE(EXCLUDED.email, identity.rdm_user_mapping.email),
                  display_name = COALESCE(EXCLUDED.display_name, identity.rdm_user_mapping.display_name)
            """)
    int upsert(
            @Bind("objectGuid") UUID objectGuid,
            @Bind("omUserId") UUID omUserId,
            @Bind("keycloakSub") UUID keycloakSub,
            @Bind("username") String username,
            @Bind("email") String email,
            @Bind("displayName") String displayName);

    /**
     * Однократная привязка {@code om_user_id} к существующей строке: срабатывает
     * только пока {@code om_user_id IS NULL} (viewer → role-holder). После первого
     * успеха значение заморожено и повторные резолвы его не трогают.
     */
    @SqlUpdate(
            "UPDATE identity.rdm_user_mapping SET om_user_id = :omUserId, last_seen_at = now()"
                    + " WHERE object_guid = :objectGuid AND om_user_id IS NULL")
    int bindOmUserId(@Bind("objectGuid") UUID objectGuid, @Bind("omUserId") UUID omUserId);

    @SqlUpdate(
            "UPDATE identity.rdm_user_mapping SET last_seen_at = now()"
                    + " WHERE object_guid = :objectGuid")
    int touchLastSeen(@Bind("objectGuid") UUID objectGuid);

    /** Read-projection для row mapper. */
    record UserMappingRow(
            UUID objectGuid,
            UUID omUserId,
            UUID keycloakSub,
            String username,
            String email,
            String displayName,
            Instant firstSeenAt,
            Instant lastSeenAt) {}
}
