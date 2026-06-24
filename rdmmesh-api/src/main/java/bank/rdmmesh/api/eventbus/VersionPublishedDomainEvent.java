package bank.rdmmesh.api.eventbus;

import java.time.OffsetDateTime;
import java.util.UUID;

import bank.rdmmesh.spec.events.VersionPublishedEvent;

/**
 * Обёртка над сгенерированным {@link VersionPublishedEvent} (rdmmesh-spec POJO),
 * чтобы in-process bus оперировал общим типом {@link DomainEvent}.
 *
 * <p>Причина обёртки идентична {@link WorkflowTransitionDomainEvent}: rdmmesh-spec
 * лежит ниже rdmmesh-api в графе зависимостей и spec-POJO не может реализовать
 * DomainEvent. Wrapper хранит сам payload (он же — outbound webhook body),
 * добавляя bus-метаданные (eventId/occurredAt).
 *
 * <p>Публикуется {@code rdmmesh-publishing} в {@code PublishingService.autoPublish}
 * после успешного CAS OWNER_APPROVED → PUBLISHED. {@code rdmmesh-audit} (E10)
 * подписан на {@link DomainEvent} глобально и пишет это в {@code audit.audit_log}.
 */
public record VersionPublishedDomainEvent(
        UUID eventId,
        OffsetDateTime occurredAt,
        VersionPublishedEvent payload)
        implements DomainEvent {}
