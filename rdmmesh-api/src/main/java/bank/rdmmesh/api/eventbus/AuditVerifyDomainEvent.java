package bank.rdmmesh.api.eventbus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Эпик E14 round 11 — event-coverage. Эмитится {@code AuditResource} при
 * вызове {@code GET /api/v1/audit/verify-chain}: фиксирует, кто и какой
 * диапазон цепочки верифицировал и с каким исходом.
 *
 * <p>Зацикливания нет: это <b>отдельный</b> тип события с собственным
 * {@code event_type=AUDIT_VERIFY_CHAIN}. Подписчик {@code rdmmesh-audit}
 * запишет его новой append-row'й с id > проверенного {@code toId}, поэтому
 * на уже-вычисленный ответ verify-chain он не влияет, а сам по себе
 * verify-chain не триггерится записью в журнал (это GET по запросу
 * пользователя).
 *
 * <p>Спец-POJO у этого события нет (внутреннее observability-событие, не
 * REST-/webhook-контракт): {@code AuditService} сериализует сам record как
 * payload (E14-compliance §3 #7 — {@code {actor, fromId, toId, verified,
 * checkedCount}}).
 */
public record AuditVerifyDomainEvent(
        UUID eventId,
        OffsetDateTime occurredAt,
        UUID actor,
        long fromId,
        long toId,
        boolean verified,
        int checkedCount)
        implements DomainEvent {}
