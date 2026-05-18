package bank.rdmmesh.api.eventbus;

import java.time.OffsetDateTime;
import java.util.UUID;

import bank.rdmmesh.spec.events.WorkflowTransitionEvent;

/**
 * Обёртка над сгенерированным {@link WorkflowTransitionEvent} (rdmmesh-spec POJO),
 * чтобы in-process bus оперировал общим типом {@link DomainEvent}.
 *
 * <p>Сам spec-POJO не может реализовать DomainEvent: rdmmesh-spec лежит ниже
 * rdmmesh-api в графе зависимостей (api уже знает про spec, обратное тащит цикл).
 * Обёртка сохраняет REST-контракт нетронутым — payload идентичен JSON-сериализации
 * spec-POJO, а bus получает типизированный {@code DomainEvent} с UUID/OffsetDateTime.
 *
 * <p>Подписчики:
 * <ul>
 *   <li>{@code rdmmesh-audit} (E10) подписывается на {@link DomainEvent} глобально;</li>
 *   <li>{@code rdmmesh-publishing} (E6) подписывается на этот тип точечно и фильтрует
 *       payload по {@code to=OWNER_APPROVED} (autopublish trigger).</li>
 * </ul>
 */
public record WorkflowTransitionDomainEvent(
        UUID eventId,
        OffsetDateTime occurredAt,
        WorkflowTransitionEvent payload)
        implements DomainEvent {}
