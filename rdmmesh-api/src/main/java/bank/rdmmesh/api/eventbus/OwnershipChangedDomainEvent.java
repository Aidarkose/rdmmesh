package bank.rdmmesh.api.eventbus;

import java.time.OffsetDateTime;
import java.util.UUID;

import bank.rdmmesh.spec.events.OwnershipChangedEvent;

/**
 * Обёртка над сгенерированным {@link OwnershipChangedEvent} (rdmmesh-spec POJO).
 * Публикуется ownership webhook receiver'ом (E7) после успешного UPSERT'а в
 * {@code catalog.domain} (для entity_type=domain) либо в
 * {@code ownership.rdm_asset_ownership} (для entity_type=table).
 *
 * <p>Помимо payload'а несёт уже-resolved {@code aggregateId}/{@code aggregateType}
 * — для {@code domain} это {@code DOMAIN:catalog.domain.id}, для {@code table} —
 * {@code CODESET:catalog.code_set.id}. Audit таким образом не дублирует FQN-парсинг
 * и lookup CodeSet'а, которые уже сделал webhook-сервис.
 *
 * <p>Для outcome'ов IGNORED / UNKNOWN_ASSET / UNSUPPORTED событие не публикуется —
 * там не было реального изменения состояния, в audit писать нечего (есть отдельный
 * журнал {@code ownership.processed_om_event} для самих webhook-вызовов).
 */
public record OwnershipChangedDomainEvent(
        UUID eventId,
        OffsetDateTime occurredAt,
        OwnershipChangedEvent payload,
        UUID aggregateId,
        String aggregateType)
        implements DomainEvent {}
