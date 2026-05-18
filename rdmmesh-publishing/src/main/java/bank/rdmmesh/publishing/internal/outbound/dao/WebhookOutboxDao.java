package bank.rdmmesh.publishing.internal.outbound.dao;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO для {@code publishing.webhook_outbox} (миграция V040).
 *
 * <p>Семантика «transactional outbox»: INSERT в outbox идёт после publish'а версии,
 * background worker дренирует таблицу с {@code FOR UPDATE SKIP LOCKED}, чтобы две
 * реплики сервиса (V1+) не дублировали доставку одной строки.
 */
public interface WebhookOutboxDao {

    String COLUMNS =
            "id, subscription_id, event_id, event_type, payload AS payload_json,"
                    + " signature, attempts, next_attempt_at, delivered_at, last_error,"
                    + " created_at";

    // payload — text (V041). НЕ jsonb: Postgres нормализует JSONB при записи,
    // что инвалидировало бы HMAC-подпись (signature считается над исходными байтами).
    @SqlUpdate(
            "INSERT INTO publishing.webhook_outbox"
                    + " (id, subscription_id, event_id, event_type, payload, signature)"
                    + " VALUES (:id, :subscriptionId, :eventId, :eventType,"
                    + "         :payloadJson, :signature)"
                    + " ON CONFLICT (subscription_id, event_id) DO NOTHING")
    int insert(
            @Bind("id") UUID id,
            @Bind("subscriptionId") UUID subscriptionId,
            @Bind("eventId") UUID eventId,
            @Bind("eventType") String eventType,
            @Bind("payloadJson") String payloadJson,
            @Bind("signature") String signature);

    /**
     * Извлекает up-to-{@code limit} due строк, помечая их «своими» через
     * {@code FOR UPDATE SKIP LOCKED}. Вызывается ВНУТРИ транзакции worker'а;
     * lock держится до commit/rollback.
     */
    @SqlQuery("SELECT " + COLUMNS + " FROM publishing.webhook_outbox"
            + " WHERE delivered_at IS NULL AND next_attempt_at <= now()"
            + " ORDER BY next_attempt_at"
            + " LIMIT :limit"
            + " FOR UPDATE SKIP LOCKED")
    @RegisterConstructorMapper(OutboxRow.class)
    List<OutboxRow> claimDue(@Bind("limit") int limit);

    @SqlUpdate("UPDATE publishing.webhook_outbox"
            + " SET delivered_at = :at, last_error = NULL"
            + " WHERE id = :id")
    int markDelivered(@Bind("id") UUID id, @Bind("at") Instant at);

    @SqlUpdate("UPDATE publishing.webhook_outbox"
            + " SET attempts = attempts + 1,"
            + "     next_attempt_at = :nextAttempt,"
            + "     last_error = :error"
            + " WHERE id = :id")
    int markRetry(
            @Bind("id") UUID id,
            @Bind("nextAttempt") Instant nextAttempt,
            @Bind("error") String error);

    /** Сдаёмся: помечаем доставленным с last_error="GIVE_UP …" чтобы остановить ретраи. */
    @SqlUpdate("UPDATE publishing.webhook_outbox"
            + " SET attempts = attempts + 1,"
            + "     delivered_at = :at,"
            + "     last_error = :error"
            + " WHERE id = :id")
    int markGivenUp(
            @Bind("id") UUID id,
            @Bind("at") Instant at,
            @Bind("error") String error);

    @SqlQuery("SELECT " + COLUMNS + " FROM publishing.webhook_outbox WHERE id = :id")
    @RegisterConstructorMapper(OutboxRow.class)
    Optional<OutboxRow> findById(@Bind("id") UUID id);

    record OutboxRow(
            UUID id,
            UUID subscriptionId,
            UUID eventId,
            String eventType,
            String payloadJson,
            String signature,
            int attempts,
            Instant nextAttemptAt,
            Instant deliveredAt,
            String lastError,
            Instant createdAt) {}
}
