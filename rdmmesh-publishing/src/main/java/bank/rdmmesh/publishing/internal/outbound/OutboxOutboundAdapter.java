package bank.rdmmesh.publishing.internal.outbound;

import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.port.OutboundPort;
import bank.rdmmesh.api.port.WebhookKeyPort;
import bank.rdmmesh.publishing.internal.outbound.dao.SubscriptionDao;
import bank.rdmmesh.publishing.internal.outbound.dao.SubscriptionDao.SubscriptionRow;
import bank.rdmmesh.publishing.internal.outbound.dao.WebhookOutboxDao;
import bank.rdmmesh.spec.events.VersionPublishedEvent;

/**
 * Реализация {@link OutboundPort} поверх {@code publishing.webhook_outbox} (V040 +
 * {@code transactional outbox} паттерн).
 *
 * <p>Алгоритм enqueue (одна транзакция):
 * <ol>
 *   <li>Сериализовать payload в стабильный JSON (ключи отсортированы, JSON-Schema-совместимо);</li>
 *   <li>Найти все active subscription'ы и отфильтровать по {@code SubscriptionFilter};</li>
 *   <li>Для каждой подходящей subscription — резолвить ключ через {@link WebhookKeyPort},
 *       посчитать HMAC-SHA-256 от payload-bytes, INSERT в outbox c уникальностью
 *       по {@code (subscription_id, event_id)} (ON CONFLICT DO NOTHING).</li>
 * </ol>
 *
 * <p><b>Транзакционная семантика.</b> SPEC говорит «INSERT в outbox в той же транзакции,
 * что publish». На пилоте — best-effort split: enqueue идёт второй транзакцией сразу
 * после {@code lifecycle.publish} (см. handoff E5 §1.4 / E6 §3 #1 / E9 — одинаковый
 * compromise для всех async-ветвей). Атомарный wrap'инг — open question для V14.
 */
public final class OutboxOutboundAdapter implements OutboundPort {

    private static final Logger log = LoggerFactory.getLogger(OutboxOutboundAdapter.class);

    /** Семантика event_type — {@code SubscriptionFilter.events}. */
    public static final String EVENT_VERSION_PUBLISHED = "VERSION_PUBLISHED";

    private final Jdbi jdbi;
    private final WebhookKeyPort keys;
    private final ObjectMapper json;

    public OutboxOutboundAdapter(Jdbi jdbi, WebhookKeyPort keys, ObjectMapper json) {
        this.jdbi = jdbi;
        this.keys = keys;
        // Стабильный JSON: ключи отсортированы — иначе HMAC неприменим к ре-сериализации
        // на стороне consumer'а через тот же ObjectMapper. Делаем ЛОКАЛЬНУЮ копию, чтобы
        // не менять глобальный mapper приложения.
        this.json = json.copy().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    }

    @Override
    public void enqueueVersionPublished(VersionPublishedEvent event) {
        // Backward-compatible: открываем собственную tx и делегируем на handle-вариант.
        jdbi.useTransaction(handle -> enqueueOnHandle(handle, event));
    }

    @Override
    public void enqueueVersionPublished(Handle handle, VersionPublishedEvent event) {
        // E14 round 5.1: работа на чужом handle — caller (PublishingService.autoPublish)
        // объединяет publish+journal+outbox в одну Postgres tx.
        enqueueOnHandle(handle, event);
    }

    private void enqueueOnHandle(Handle handle, VersionPublishedEvent event) {
        if (event == null) throw new IllegalArgumentException("event is null");
        byte[] payload;
        try {
            payload = json.writeValueAsBytes(event);
        } catch (Exception e) {
            throw new IllegalStateException("cannot serialize VersionPublishedEvent", e);
        }
        String payloadJson = new String(payload, java.nio.charset.StandardCharsets.UTF_8);
        UUID eventId = UUID.fromString(event.getEventId());
        String domainName = event.getDomainName();
        String codesetName = event.getCodesetName();

        SubscriptionDao subs = handle.attach(SubscriptionDao.class);
        WebhookOutboxDao outbox = handle.attach(WebhookOutboxDao.class);

        int matched = 0;
        int enqueued = 0;
        for (SubscriptionRow s : subs.findActive()) {
            var filter = SubscriptionFilterMatcher.parse(json, s.filterJson());
            if (!SubscriptionFilterMatcher.matches(
                    filter, domainName, codesetName, EVENT_VERSION_PUBLISHED)) {
                continue;
            }
            matched++;

            byte[] key;
            try {
                key = keys.resolveKey(s.secretId());
            } catch (RuntimeException e) {
                log.warn("outbound: skip subscription {} ({}): не удалось резолвить secret_id={}: {}",
                        s.id(), s.url(), s.secretId(), e.toString());
                continue;
            }
            String signature = WebhookHmac.hexSignature(payload, key);
            int rows = outbox.insert(
                    UUID.randomUUID(), s.id(), eventId,
                    EVENT_VERSION_PUBLISHED, payloadJson, signature);
            if (rows > 0) enqueued++;
        }
        log.info("outbound: enqueueVersionPublished event_id={} version={} matched={} enqueued={}",
                eventId, event.getVersion(), matched, enqueued);
    }
}
