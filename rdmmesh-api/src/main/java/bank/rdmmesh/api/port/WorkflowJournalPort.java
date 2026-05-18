package bank.rdmmesh.api.port;

import java.util.UUID;

import org.jdbi.v3.core.Handle;

/**
 * Append-only порт в {@code workflow.workflow_transition}. Используется только
 * системными постпроцессами (publishing E6, future hotfix-flow) для логирования
 * автоматически сделанных переходов. Высокоуровневый {@code WorkflowPort.transition()}
 * сам пишет в этот журнал, и в нормальном flow E6 sub-action publish/deprecate
 * не использует {@code WorkflowPort.transition()} (там нет UI-инициированного перехода).
 *
 * <p>Контракт: журнал append-only, поэтому apply/replay safe; никакого business-validation
 * тут нет — это исключительно тех. лог.
 */
public interface WorkflowJournalPort {

    /**
     * Записать post-hoc transition (publish, deprecate, hotfix). Возвращает event_id
     * сгенерированный сервером (UUID v4) — пригодится в outbound webhook payload'ах.
     */
    UUID recordSystemTransition(
            UUID versionId,
            UUID codesetId,
            UUID domainId,
            String fromStatus,
            String toStatus,
            String action,
            UUID actor,
            String comment);

    /**
     * E14 round 5.1 — Handle-overload. PublishingService.autoPublish использует
     * его для atomic-tx с publish/deprecate/outbound.
     */
    UUID recordSystemTransition(
            Handle handle,
            UUID versionId,
            UUID codesetId,
            UUID domainId,
            String fromStatus,
            String toStatus,
            String action,
            UUID actor,
            String comment);
}
