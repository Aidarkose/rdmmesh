package bank.rdmmesh.audit.internal;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import bank.rdmmesh.api.eventbus.AuditVerifyDomainEvent;
import bank.rdmmesh.api.eventbus.ClosureRebuildDomainEvent;
import bank.rdmmesh.api.eventbus.DomainEvent;
import bank.rdmmesh.api.eventbus.OwnershipChangedDomainEvent;
import bank.rdmmesh.api.eventbus.VersionPublishedDomainEvent;
import bank.rdmmesh.api.eventbus.WorkflowTransitionDomainEvent;
import bank.rdmmesh.spec.events.OwnershipChangedEvent;
import bank.rdmmesh.spec.events.VersionPublishedEvent;
import bank.rdmmesh.spec.events.WorkflowTransitionEvent;

import static org.assertj.core.api.Assertions.assertThat;

final class AuditEventClassifierTest {

    @Test
    void workflow_transition_extracts_version_id_and_actor() {
        UUID versionId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        WorkflowTransitionEvent payload = new WorkflowTransitionEvent()
                .withVersionId(versionId.toString())
                .withActor(actor.toString());
        DomainEvent evt = new WorkflowTransitionDomainEvent(
                UUID.randomUUID(), OffsetDateTime.now(), payload);

        var c = AuditEventClassifier.classify(evt);

        assertThat(c.eventType()).isEqualTo("WORKFLOW_TRANSITION");
        assertThat(c.aggregateType()).isEqualTo("VERSION");
        assertThat(c.aggregateId()).isEqualTo(versionId);
        assertThat(c.actor()).isEqualTo(actor);
    }

    @Test
    void workflow_transition_with_null_payload_returns_aggregate_type_only() {
        DomainEvent evt = new WorkflowTransitionDomainEvent(
                UUID.randomUUID(), OffsetDateTime.now(), null);

        var c = AuditEventClassifier.classify(evt);

        assertThat(c.eventType()).isEqualTo("WORKFLOW_TRANSITION");
        assertThat(c.aggregateType()).isEqualTo("VERSION");
        assertThat(c.aggregateId()).isNull();
        assertThat(c.actor()).isNull();
    }

    @Test
    void workflow_transition_garbage_uuid_does_not_throw() {
        WorkflowTransitionEvent payload = new WorkflowTransitionEvent()
                .withVersionId("not-a-uuid")
                .withActor("");
        DomainEvent evt = new WorkflowTransitionDomainEvent(
                UUID.randomUUID(), OffsetDateTime.now(), payload);

        var c = AuditEventClassifier.classify(evt);

        assertThat(c.aggregateId()).isNull();
        assertThat(c.actor()).isNull();
    }

    @Test
    void version_published_extracts_version_id_and_published_by() {
        UUID versionId = UUID.randomUUID();
        UUID publishedBy = UUID.randomUUID();
        VersionPublishedEvent payload = new VersionPublishedEvent()
                .withEventId(UUID.randomUUID().toString())
                .withVersionId(versionId.toString())
                .withPublishedBy(publishedBy.toString());
        DomainEvent evt = new VersionPublishedDomainEvent(
                UUID.randomUUID(), OffsetDateTime.now(), payload);

        var c = AuditEventClassifier.classify(evt);

        assertThat(c.eventType()).isEqualTo("VERSION_PUBLISHED");
        assertThat(c.aggregateType()).isEqualTo("VERSION");
        assertThat(c.aggregateId()).isEqualTo(versionId);
        assertThat(c.actor()).isEqualTo(publishedBy);
    }

    @Test
    void version_published_without_published_by_yields_null_actor() {
        VersionPublishedEvent payload = new VersionPublishedEvent()
                .withVersionId(UUID.randomUUID().toString());
        DomainEvent evt = new VersionPublishedDomainEvent(
                UUID.randomUUID(), OffsetDateTime.now(), payload);

        var c = AuditEventClassifier.classify(evt);

        assertThat(c.actor()).isNull();
    }

    @Test
    void ownership_changed_uses_resolved_aggregate_from_wrapper() {
        UUID codesetId = UUID.randomUUID();
        OwnershipChangedEvent payload = new OwnershipChangedEvent()
                .withEventId("evt-x")
                .withFullyQualifiedName("rdmmesh.risk.foo");
        DomainEvent evt = new OwnershipChangedDomainEvent(
                UUID.randomUUID(), OffsetDateTime.now(), payload, codesetId, "CODESET");

        var c = AuditEventClassifier.classify(evt);

        assertThat(c.eventType()).isEqualTo("OWNERSHIP_CHANGED");
        assertThat(c.aggregateType()).isEqualTo("CODESET");
        assertThat(c.aggregateId()).isEqualTo(codesetId);
        assertThat(c.actor()).isNull();
    }

    @Test
    void audit_verify_chain_classified_with_actor_and_audit_aggregate() {
        UUID actor = UUID.randomUUID();
        DomainEvent evt = new AuditVerifyDomainEvent(
                UUID.randomUUID(), OffsetDateTime.now(), actor, 1L, 42L, true, 41);

        var c = AuditEventClassifier.classify(evt);

        assertThat(c.eventType()).isEqualTo("AUDIT_VERIFY_CHAIN");
        assertThat(c.aggregateType()).isEqualTo("AUDIT");
        assertThat(c.aggregateId()).isNull(); // range, не один asset
        assertThat(c.actor()).isEqualTo(actor);
    }

    @Test
    void closure_rebuild_classified_with_version_aggregate_and_admin_actor() {
        UUID versionId = UUID.randomUUID();
        UUID admin = UUID.randomUUID();
        DomainEvent evt = new ClosureRebuildDomainEvent(
                UUID.randomUUID(), OffsetDateTime.now(), admin, versionId, 5, 5, 12);

        var c = AuditEventClassifier.classify(evt);

        assertThat(c.eventType()).isEqualTo("CLOSURE_REBUILD");
        assertThat(c.aggregateType()).isEqualTo("VERSION");
        assertThat(c.aggregateId()).isEqualTo(versionId);
        assertThat(c.actor()).isEqualTo(admin);
    }

    @Test
    void unknown_event_falls_back_to_simple_class_name() {
        DomainEvent evt = new UnknownEvent(UUID.randomUUID(), OffsetDateTime.now());

        var c = AuditEventClassifier.classify(evt);

        assertThat(c.eventType()).isEqualTo("UnknownEvent");
        assertThat(c.aggregateType()).isNull();
        assertThat(c.aggregateId()).isNull();
    }

    /** Sentinel-тип для проверки fallback'а; имя класса фиксирует контракт «event_type = simpleName». */
    private record UnknownEvent(UUID eventId, OffsetDateTime occurredAt) implements DomainEvent {}
}
