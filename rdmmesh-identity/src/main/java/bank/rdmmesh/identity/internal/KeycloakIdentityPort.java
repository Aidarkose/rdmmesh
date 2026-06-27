package bank.rdmmesh.identity.internal;

import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import bank.rdmmesh.api.port.IdentityPort;
import bank.rdmmesh.identity.internal.dao.UserMappingDao;
import bank.rdmmesh.identity.internal.dao.UserMappingDao.UserMappingRow;
import bank.rdmmesh.identity.internal.jwt.JwtValidator;
import bank.rdmmesh.identity.internal.om.OpenMetadataUserClient;

/**
 * Реализация {@link IdentityPort} поверх Keycloak JWT и OpenMetadata REST.
 *
 * <p>Алгоритм {@link #authenticate(String)}:
 *
 * <ol>
 *   <li>Валидация JWT через {@link JwtValidator} (signature/iss/aud/exp + required claims).
 *   <li>Lookup в {@code identity.rdm_user_mapping} по {@code object_guid} (AD objectGUID,
 *       claim {@code oid}) — стабильный якорь, fast path.
 *   <li>На miss / пока {@code om_user_id IS NULL} + наличии OM-клиента — REST в OM по
 *       {@code preferred_username}; попадание фиксируется один раз и далее замораживается.
 *   <li>Если OM нет / не нашёл — пользователь остаётся <b>viewer</b> с {@code om_user_id = NULL}
 *       (read-only, истории не порождает). Provisional UUID больше не выдаётся.
 * </ol>
 *
 * <p>Решения {@link #resolveOmUserId(UUID)} / {@link #resolveKeycloakSub(UUID)} — read-only из БД,
 * используются background-воркерами и webhook-приёмником OM (см. {@code rdmmesh-ownership}).
 *
 * <p>Класс thread-safe, держится в одном экземпляре на инстанс сервиса (создаётся в
 * {@code IdentityModule}).
 */
public final class KeycloakIdentityPort implements IdentityPort {

    private static final Logger log = LoggerFactory.getLogger(KeycloakIdentityPort.class);

    private final Jdbi jdbi;
    private final JwtValidator jwtValidator;
    private final OpenMetadataUserClient omClient;
    private final String groupsClaim;
    private final Cache<UUID, AuthenticatedUser> authCache;

    public KeycloakIdentityPort(
            Jdbi jdbi,
            JwtValidator jwtValidator,
            OpenMetadataUserClient omClient,
            String groupsClaim) {
        this.jdbi = jdbi;
        this.jwtValidator = jwtValidator;
        this.omClient = omClient;
        this.groupsClaim = groupsClaim;
        // Кэш по object_guid (стабильный AD-якорь), а не по token — токены короткоживущие.
        // Размер ограничен — типовой банковский домен ≤ 50k активных юзеров; 10k — запас.
        this.authCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .build();
    }

    @Override
    public AuthenticatedUser authenticate(String bearerToken) {
        var resolved = jwtValidator.validate(bearerToken);
        return authCache.get(resolved.objectGuid(), guid -> resolve(resolved));
    }

    private AuthenticatedUser resolve(JwtValidator.Resolved resolved) {
        UUID objectGuid = resolved.objectGuid();
        UUID keycloakSub = resolved.subject();
        String resolvedUsername = resolved.preferredUsername();
        // preferred_username входит в requiredClaims — но оставим safety-net.
        final String username =
                (resolvedUsername == null || resolvedUsername.isBlank())
                        ? "unknown@" + objectGuid
                        : resolvedUsername;

        Optional<UserMappingRow> existing = jdbi.withExtension(
                UserMappingDao.class, dao -> dao.findByObjectGuid(objectGuid));

        if (existing.isPresent()) {
            UUID omUserId = existing.get().omUserId();
            // Резолвим до первого успеха: пока om_user_id пуст — пробуем привязать
            // (роль в OM могли назначить уже после первого логина). После успеха —
            // значение заморожено (bindOmUserId срабатывает только при om_user_id IS NULL).
            if (omUserId == null) {
                omUserId = lookupOmUserId(username).orElse(null);
                if (omUserId != null) {
                    final UUID bound = omUserId;
                    jdbi.useExtension(UserMappingDao.class,
                            dao -> dao.bindOmUserId(objectGuid, bound));
                    log.info("identity: om_user_id привязан username={} object_guid={} om_user_id={}",
                            username, objectGuid, bound);
                }
            }
            jdbi.useExtension(UserMappingDao.class, dao -> dao.touchLastSeen(objectGuid));
            return new AuthenticatedUser(omUserId, keycloakSub, username, resolved.groups());
        }

        // Первый логин под этим objectGUID: нет роли в OM → viewer (om_user_id=NULL).
        UUID omUserId = lookupOmUserId(username).orElse(null);
        jdbi.useExtension(UserMappingDao.class, dao -> dao.upsert(
                objectGuid,
                omUserId,
                keycloakSub,
                username,
                resolved.email(),
                resolved.displayName()));
        log.info("identity: новый пользователь username={} object_guid={} om_user_id={}",
                username, objectGuid, omUserId);
        return new AuthenticatedUser(omUserId, keycloakSub, username, resolved.groups());
    }

    /** Резолв реального OM User.id по имени учётки. Пусто → пользователь остаётся viewer. */
    private Optional<UUID> lookupOmUserId(String username) {
        if (omClient == null) {
            log.debug("identity: OM-клиент не настроен; {} — viewer (om_user_id=NULL)", username);
            return Optional.empty();
        }
        Optional<UUID> fromOm = omClient.findUserIdByName(username);
        if (fromOm.isEmpty()) {
            log.debug("identity: OM не вернул user.id для {}; viewer (om_user_id=NULL)", username);
        }
        return fromOm;
    }

    @Override
    public Optional<UUID> resolveOmUserId(UUID keycloakSub) {
        return jdbi.withExtension(
                        UserMappingDao.class, dao -> dao.findByKeycloakSub(keycloakSub))
                .map(UserMappingRow::omUserId);
    }

    @Override
    public Optional<UUID> resolveKeycloakSub(UUID omUserId) {
        return jdbi.withExtension(UserMappingDao.class, dao -> dao.findByOmUserId(omUserId))
                .map(UserMappingRow::keycloakSub);
    }

    /** Имя claim'а с группами (из конфига); экспонируется для wiring с Dropwizard-auth filter. */
    public String groupsClaim() {
        return groupsClaim;
    }

    /** Сбросить кэш аутентификации (например, при смене ownership через OM webhook). */
    public void invalidateAuthCache() {
        authCache.invalidateAll();
    }

    public void invalidateAuthCache(UUID keycloakSub) {
        authCache.invalidate(keycloakSub);
    }
}
