package bank.rdmmesh.workflow.internal;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import bank.rdmmesh.api.port.WorkflowPort;
import bank.rdmmesh.spec.entity.AssetOwnership;
import bank.rdmmesh.spec.events.WorkflowTransitionEvent;
import bank.rdmmesh.workflow.internal.dao.ApprovalTaskDao.ApprovalTaskRow;
import bank.rdmmesh.workflow.internal.service.WorkflowService;

/**
 * Реализация {@link WorkflowPort} — тонкий адаптер поверх {@link WorkflowService}.
 *
 * <p>Контракт {@code WorkflowPort.transition(...)} принимает {@code targetStatus} и
 * {@code actor}, но не базовые роли актора. Чтобы {@link WorkflowService} мог
 * выполнить полный role gate (asset-level + base-level fallback), REST-resource
 * вызывает service напрямую, а этот порт оставлен для модулей (E10 Audit) и
 * unit-тестов, которые не имеют JWT в руках.
 */
public final class PostgresWorkflowPort implements WorkflowPort {

    private final WorkflowService service;

    public PostgresWorkflowPort(WorkflowService service) {
        this.service = service;
    }

    @Override
    public WorkflowTransitionEvent transition(
            UUID versionId, String targetStatus, UUID actor, String comment) {
        // Без base-ролей: будет проходить только если у актора есть asset-level роль
        // в rdm_asset_ownership (provisional OWNER на собственноручно созданных CodeSet'ах).
        // REST-resource предпочитает прямой вызов service.transition(..., baseRoles).
        return service.transition(versionId, targetStatus, actor, Set.of(), comment);
    }

    @Override
    public List<WorkflowTransitionEvent> history(UUID versionId) {
        return service.history(versionId);
    }

    @Override
    public Optional<AssetOwnership> openTaskFor(UUID versionId, String requiredRole) {
        Optional<ApprovalTaskRow> row = service.openTaskFor(versionId, requiredRole);
        // Контракт типа AssetOwnership здесь странный — это артефакт оригинального
        // черновика порта (E1). В практике "My Tasks" дёргается через REST resource,
        // не через этот метод. Возвращаем Optional.empty() как nominal-stub.
        if (row.isEmpty()) return Optional.empty();
        return Optional.empty();
    }
}
