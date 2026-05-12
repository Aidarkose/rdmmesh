package bank.rdmmesh.audit.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.eventbus.DomainEvent;
import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.eventbus.OwnershipChangedDomainEvent;
import bank.rdmmesh.api.eventbus.VersionPublishedDomainEvent;
import bank.rdmmesh.api.eventbus.WorkflowTransitionDomainEvent;
import bank.rdmmesh.audit.internal.dao.AuditLogDao;

/**
 * Эпик E10 (Audit). Подписывается на {@link DomainEvent} глобально и пишет каждое
 * событие в {@code audit.audit_log} (append-only, INSERT-only grants + триггеры
 * против UPDATE/DELETE/TRUNCATE на уровне Postgres).
 *
 * <p>Эпик E14 round 1 расширил INSERT криптографической hash-chain (V072): каждая
 * новая запись несёт {@code prev_hash} (hash предыдущей записи) и {@code entry_hash}
 * (SHA-256 от canonical_input текущей). Это делает журнал tamper-evident: модификация
 * одной строки разрушает цепочку всех последующих, и verify-endpoint
 * ({@code GET /api/v1/audit/verify-chain}) это обнаруживает.
 *
 * <p>Сериализация payload'а: для известных типов берём оригинальный spec-POJO
 * (REST-/webhook-шейп), для неизвестных — generic JSON-представление через Jackson.
 * Дополнительно сохраняется byte-stable форма в колонке {@code payload_canonical}
 * (sorted keys, no whitespace) — она же входит в hash-input.
 *
 * <p>Idempotency: INSERT использует {@code ON CONFLICT (event_id, event_type) DO NOTHING}
 * (UNIQUE-индекс audit_log_event_id_uq, миграция V071). Replay одного и того же
 * события не создаёт дубликата. {@code prev_hash}/{@code entry_hash} в этом случае
 * не сдвигаются — сервис заранее проверяет «уже записано?» через DAO insert,
 * получает rows=0 и пропускает.
 *
 * <p>Concurrency: chain-write идёт под {@code pg_advisory_xact_lock} на время
 * транзакции. Это сериализует параллельные publish'ы (например, два workflow_transition
 * из одной 4-eyes цепочки, прилетевшие почти одновременно). Lock держится миллисекунды
 * (один SELECT + один INSERT) и не блокирует другие части системы.
 *
 * <p>Транзакции: subscriber выполняется синхронно в потоке publisher'а, в собственной
 * транзакции через {@link Jdbi#inTransaction}. Сбой INSERT'а в audit НЕ откатывает
 * работу publisher'а — {@link bank.rdmmesh.api.eventbus.EventBus} ловит исключения
 * подписчиков и логирует их (см. {@code SyncEventBus}). Это сознательная компромиссная
 * позиция пилота: best-effort audit предпочтительнее блокировки бизнес-операции при
 * отказе аудит-БД.
 */
public final class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    /**
     * Стабильный ключ advisory-lock'а для chain-write. Литерал выбран так, чтобы
     * не пересекаться с advisory-lock'ами других модулей (если они когда-то
     * появятся): hash('audit.chain.v1', sha256) → первые 8 байт как long.
     * Конкретное значение неважно — важно, что оно константно.
     */
    private static final long CHAIN_LOCK_KEY = 0x4155_4449_5443_4841L; // "AUDITCHA" ASCII

    private final Jdbi jdbi;
    private final ObjectMapper json;
    private final AuditChainHasher hasher;

    public AuditService(Jdbi jdbi, ObjectMapper json) {
        this.jdbi = jdbi;
        this.json = json;
        this.hasher = new AuditChainHasher(AuditChainHasher.defaultMapper());
    }

    public void registerOn(EventBus bus) {
        bus.subscribe(DomainEvent.class, this::onEvent);
    }

    void onEvent(DomainEvent event) {
        AuditEventClassifier.Classification c = AuditEventClassifier.classify(event);
        Object payload = extractPayload(event);
        final String payloadCanonical = hasher.canonicalPayload(payload);
        final String payloadJson;
        try {
            payloadJson = json.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("audit: payload serialisation (non-canonical) failed for {} ({}): {}",
                    c.eventType(), event.eventId(), e.toString());
            return;
        }

        try {
            int rows = jdbi.inTransaction(handle -> {
                // Сериализация одной транзакции = линейная цепочка hash'ей.
                handle.execute("SELECT pg_advisory_xact_lock(?)", CHAIN_LOCK_KEY);

                AuditLogDao dao = handle.attach(AuditLogDao.class);
                String prevHash = dao.findLastEntryHash().orElse(null);
                String entryHash = hasher.computeEntryHash(
                        prevHash,
                        event.eventId(),
                        c.eventType(),
                        payloadCanonical,
                        event.occurredAt());

                return dao.insert(
                        event.eventId(),
                        c.eventType(),
                        c.aggregateType(),
                        c.aggregateId(),
                        c.actor(),
                        event.occurredAt(),
                        payloadJson,
                        payloadCanonical,
                        prevHash,
                        entryHash);
            });
            if (rows == 0) {
                log.debug("audit: duplicate event_id={} event_type={} — пропущено по UNIQUE",
                        event.eventId(), c.eventType());
            }
        } catch (RuntimeException e) {
            log.warn("audit: INSERT failed for {} ({}): {}",
                    c.eventType(), event.eventId(), e.toString());
        }
    }

    private static Object extractPayload(DomainEvent event) {
        // Сериализуем оригинальный spec-POJO напрямую — он же является body
        // outbound webhook'а / REST-event'а. Audit держит ровно тот контекст,
        // который видели потребители событий.
        if (event instanceof WorkflowTransitionDomainEvent w)  return w.payload();
        if (event instanceof VersionPublishedDomainEvent v)    return v.payload();
        if (event instanceof OwnershipChangedDomainEvent o)    return o.payload();
        return event;
    }
}
