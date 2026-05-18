package bank.rdmmesh.api.eventbus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Эпик E14 round 11 — event-coverage (закрывает E13.3 §3 #6 /
 * E14-compliance §3 #7). Эмитится {@code ClosureAdminResource} после
 * disaster-recovery rebuild'а closure-таблицы иерархии: до round 11
 * операция только {@code log.warn}'илась и в audit-журнал не попадала, хотя
 * это admin-mutation closure-структуры.
 *
 * <p>Подписчик {@code rdmmesh-audit} классифицирует как
 * {@code event_type=CLOSURE_REBUILD}, {@code aggregate_type=VERSION},
 * {@code aggregate_id=versionId}, {@code actor=admin}. Спец-POJO нет —
 * сам record и есть payload ({@code {versionId, removed, inserted, total}}).
 */
public record ClosureRebuildDomainEvent(
        UUID eventId,
        OffsetDateTime occurredAt,
        UUID actor,
        UUID versionId,
        int removed,
        int inserted,
        int total)
        implements DomainEvent {}
