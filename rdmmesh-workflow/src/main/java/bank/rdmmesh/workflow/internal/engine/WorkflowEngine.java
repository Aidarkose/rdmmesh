package bank.rdmmesh.workflow.internal.engine;

import java.util.Set;
import java.util.UUID;

import bank.rdmmesh.spec.events.WorkflowTransitionEvent;

/**
 * Seam движка workflow (V2 / BR-18, ADR-009). Абстрагирует, <i>чем</i>
 * приводится в действие 4-eyes-переход: enum-{@code StateMachine} (дефолт,
 * пилот) или BPMN-движок Flowable (per-domain шаблоны — следующий раунд).
 *
 * <p><b>Инвариант, который не меняется ни одной реализацией:</b> сам переход
 * (валидация — self-approval / role-gate / no-bypass — и атомарные side-эффекты
 * CAS+journal+task+event) выполняет {@code WorkflowService.transition}. Flowable
 * добавляет только топологию/инстансы/историю и место, куда в следующем раунде
 * сядут кастомные per-domain BPMN; бизнес-логика и аудит — без рефакторинга
 * (SPEC ADR-004 / §5.2 «миграция за WorkflowPort, без рефакторинга»).
 */
public interface WorkflowEngine {

    /**
     * Сигнатура зеркалит {@code WorkflowService.transition} (включая
     * {@code baseRoles} — REST-resource резолвит их из JWT-принципала).
     */
    WorkflowTransitionEvent transition(
            UUID versionId,
            String targetStatus,
            UUID actor,
            Set<String> baseRoles,
            String comment);
}
