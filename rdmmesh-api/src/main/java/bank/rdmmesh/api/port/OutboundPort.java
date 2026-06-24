package bank.rdmmesh.api.port;

import org.jdbi.v3.core.Handle;

import bank.rdmmesh.spec.events.VersionPublishedEvent;

/**
 * Delivery of business events to external consumer systems. MVP adapter is a webhook
 * dispatcher backed by the {@code publishing.webhook_outbox} transactional outbox.
 * V2 may swap in a Kafka adapter behind the same port (SPEC ADR-004 + §5.2 V2).
 */
public interface OutboundPort {

    /**
     * Persist the event into the outbox in the SAME transaction as the publish. The
     * background worker drains the outbox with at-least-once + idempotent semantics.
     */
    void enqueueVersionPublished(VersionPublishedEvent event);

    /**
     * E14 round 5.1 — Handle-overload. PublishingService.autoPublish использует
     * его, чтобы outbox INSERT попал в одну Postgres tx с publish/deprecate/journal.
     * Закрывает split-tx (handoff E6 §3 #1 / E9 §3 #3): теперь либо все операции
     * commit'нуты, либо ни одна (rollback на любом сбое).
     */
    void enqueueVersionPublished(Handle handle, VersionPublishedEvent event);
}
