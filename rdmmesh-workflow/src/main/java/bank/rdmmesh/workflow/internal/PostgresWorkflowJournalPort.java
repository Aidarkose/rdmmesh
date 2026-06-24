package bank.rdmmesh.workflow.internal;

import java.util.UUID;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.api.port.WorkflowJournalPort;
import bank.rdmmesh.workflow.internal.dao.WorkflowTransitionDao;

/**
 * Реализация {@link WorkflowJournalPort} над {@code workflow.workflow_transition}.
 * Append-only INSERT без бизнес-валидации — нужен publishing-модулю (E6) для
 * логирования автоматических publish/deprecate переходов в той же транзакции,
 * где статус менялся.
 */
public final class PostgresWorkflowJournalPort implements WorkflowJournalPort {

    private final Jdbi jdbi;

    public PostgresWorkflowJournalPort(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public UUID recordSystemTransition(
            UUID versionId,
            UUID codesetId,
            UUID domainId,
            String fromStatus,
            String toStatus,
            String action,
            UUID actor,
            String comment) {
        UUID eventId = UUID.randomUUID();
        jdbi.useExtension(WorkflowTransitionDao.class, dao -> dao.insert(
                eventId, versionId, codesetId, domainId,
                fromStatus, toStatus, action, actor, comment));
        return eventId;
    }

    @Override
    public UUID recordSystemTransition(
            Handle handle,
            UUID versionId,
            UUID codesetId,
            UUID domainId,
            String fromStatus,
            String toStatus,
            String action,
            UUID actor,
            String comment) {
        // E14 round 5.1: работа на чужом handle. PublishingService.autoPublish
        // объединяет publish+journal+outbox в одну Postgres tx.
        UUID eventId = UUID.randomUUID();
        handle.attach(WorkflowTransitionDao.class).insert(
                eventId, versionId, codesetId, domainId,
                fromStatus, toStatus, action, actor, comment);
        return eventId;
    }
}
