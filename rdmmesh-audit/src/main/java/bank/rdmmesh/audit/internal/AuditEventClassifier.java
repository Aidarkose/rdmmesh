package bank.rdmmesh.audit.internal;

import java.util.UUID;

import bank.rdmmesh.api.eventbus.AuditVerifyDomainEvent;
import bank.rdmmesh.api.eventbus.ClosureRebuildDomainEvent;
import bank.rdmmesh.api.eventbus.DomainEvent;
import bank.rdmmesh.api.eventbus.OwnershipChangedDomainEvent;
import bank.rdmmesh.api.eventbus.VersionDeletedDomainEvent;
import bank.rdmmesh.api.eventbus.VersionPublishedDomainEvent;
import bank.rdmmesh.api.eventbus.WorkflowTransitionDomainEvent;
import bank.rdmmesh.spec.events.OwnershipChangedEvent;
import bank.rdmmesh.spec.events.VersionPublishedEvent;
import bank.rdmmesh.spec.events.WorkflowTransitionEvent;

/**
 * Pure-mapping {@link DomainEvent} → доменно-нейтральные поля {@link Classification},
 * которые ложатся в колонки {@code audit.audit_log}.
 *
 * <p>Без БД, без сериализации. Сериализация payload'а — забота {@code AuditService}.
 *
 * <p>Если event-тип не известен (например, неожиданная подписка либо тестовый
 * stub) — fallback'ом event_type = simple class name, остальные поля null. Audit
 * по-прежнему фиксирует факт прихода события в журнале, чтобы пропусков не было.
 */
public final class AuditEventClassifier {

    private AuditEventClassifier() {}

    public static Classification classify(DomainEvent event) {
        if (event instanceof WorkflowTransitionDomainEvent w) {
            return classifyWorkflow(w);
        }
        if (event instanceof VersionPublishedDomainEvent v) {
            return classifyVersionPublished(v);
        }
        if (event instanceof OwnershipChangedDomainEvent o) {
            return classifyOwnership(o);
        }
        if (event instanceof AuditVerifyDomainEvent a) {
            // aggregate = весь журнал (range), не один asset → aggregateId=null.
            return new Classification("AUDIT_VERIFY_CHAIN", "AUDIT", null, a.actor());
        }
        if (event instanceof ClosureRebuildDomainEvent cr) {
            return new Classification(
                    "CLOSURE_REBUILD", "VERSION", cr.versionId(), cr.actor());
        }
        if (event instanceof VersionDeletedDomainEvent vd) {
            return new Classification(
                    "VERSION_DELETED", "VERSION", vd.versionId(), vd.actor());
        }
        return new Classification(
                event.getClass().getSimpleName(), null, null, null);
    }

    private static Classification classifyWorkflow(WorkflowTransitionDomainEvent event) {
        WorkflowTransitionEvent payload = event.payload();
        if (payload == null) {
            return new Classification("WORKFLOW_TRANSITION", "VERSION", null, null);
        }
        return new Classification(
                "WORKFLOW_TRANSITION",
                "VERSION",
                tryUuid(payload.getVersionId()),
                tryUuid(payload.getActor()));
    }

    private static Classification classifyVersionPublished(VersionPublishedDomainEvent event) {
        VersionPublishedEvent payload = event.payload();
        if (payload == null) {
            return new Classification("VERSION_PUBLISHED", "VERSION", null, null);
        }
        return new Classification(
                "VERSION_PUBLISHED",
                "VERSION",
                tryUuid(payload.getVersionId()),
                tryUuid(payload.getPublishedBy()));
    }

    private static Classification classifyOwnership(OwnershipChangedDomainEvent event) {
        OwnershipChangedEvent payload = event.payload();
        // actor=null: webhook от OM-системы, не от человеческого пользователя.
        // Конкретный администратор в OM, который сделал назначение, в payload'е
        // не приходит; идентификация — по event_id и source IP в access-log'е.
        return new Classification(
                "OWNERSHIP_CHANGED",
                event.aggregateType(),
                event.aggregateId(),
                /* actor */ null);
    }

    private static UUID tryUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public record Classification(
            String eventType,
            String aggregateType,
            UUID aggregateId,
            UUID actor) {}
}
