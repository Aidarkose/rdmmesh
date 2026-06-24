package bank.rdmmesh.api.eventbus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Эпик E16.3 (V2 / BR-18 round 3) — DRAFT-версия удалена
 * ({@code AuthoringService.deleteDraft}). Эмитится ПОСЛЕ commit'а delete-tx.
 *
 * <p>Подписчики:
 * <ul>
 *   <li>{@code rdmmesh-audit} — классифицирует {@code event_type=
 *       VERSION_DELETED}, {@code aggregate=VERSION}, {@code actor}
 *       (append-only audit, SPEC §3.8);</li>
 *   <li>при {@code engine=flowable} — composition root гасит осиротевший
 *       Flowable-инстанс с businessKey=versionId (round-3 cleanup;
 *       best-effort, не блокирует удаление версии).</li>
 * </ul>
 *
 * <p>Спец-POJO нет — record и есть payload ({@code {versionId}}).
 */
public record VersionDeletedDomainEvent(
        UUID eventId,
        OffsetDateTime occurredAt,
        UUID versionId,
        UUID actor)
        implements DomainEvent {}
