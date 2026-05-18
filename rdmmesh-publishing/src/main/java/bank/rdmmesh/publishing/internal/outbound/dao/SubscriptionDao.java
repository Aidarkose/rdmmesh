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
 * DAO для {@code publishing.webhook_subscription} (миграция V040).
 *
 * <p>В БД лежит {@code secret_id} — pointer на ключ в Vault/SOPS; сам HMAC-ключ
 * никогда не хранится. Резолвинг pointer→ключ — задача {@code WebhookKeyPort}.
 */
public interface SubscriptionDao {

    String COLUMNS =
            "id, url, secret_id, filter::text AS filter_json, active,"
                    + " created_at, created_by,"
                    + " last_delivery_at, last_delivery_status";

    @SqlQuery("SELECT " + COLUMNS + " FROM publishing.webhook_subscription"
            + " ORDER BY created_at DESC")
    @RegisterConstructorMapper(SubscriptionRow.class)
    List<SubscriptionRow> findAll();

    @SqlQuery("SELECT " + COLUMNS + " FROM publishing.webhook_subscription"
            + " WHERE active = true"
            + " ORDER BY created_at")
    @RegisterConstructorMapper(SubscriptionRow.class)
    List<SubscriptionRow> findActive();

    @SqlQuery("SELECT " + COLUMNS + " FROM publishing.webhook_subscription"
            + " WHERE id = :id")
    @RegisterConstructorMapper(SubscriptionRow.class)
    Optional<SubscriptionRow> findById(@Bind("id") UUID id);

    @SqlUpdate(
            "INSERT INTO publishing.webhook_subscription"
                    + " (id, url, secret_id, filter, active, created_by)"
                    + " VALUES (:id, :url, :secretId, :filterJson::jsonb, :active, :createdBy)")
    int insert(
            @Bind("id") UUID id,
            @Bind("url") String url,
            @Bind("secretId") String secretId,
            @Bind("filterJson") String filterJson,
            @Bind("active") boolean active,
            @Bind("createdBy") UUID createdBy);

    @SqlUpdate("UPDATE publishing.webhook_subscription"
            + " SET active = false"
            + " WHERE id = :id")
    int deactivate(@Bind("id") UUID id);

    @SqlUpdate("UPDATE publishing.webhook_subscription"
            + " SET last_delivery_at = :at, last_delivery_status = :status"
            + " WHERE id = :id")
    int markDelivery(
            @Bind("id") UUID id,
            @Bind("at") Instant at,
            @Bind("status") String status);

    /** Read-only snapshot для сервиса/worker'а. */
    record SubscriptionRow(
            UUID id,
            String url,
            String secretId,
            String filterJson,
            boolean active,
            Instant createdAt,
            UUID createdBy,
            Instant lastDeliveryAt,
            String lastDeliveryStatus) {}
}
