package bank.rdmmesh.workflow.internal.engine;

import java.util.Set;
import java.util.UUID;

import bank.rdmmesh.spec.events.WorkflowTransitionEvent;
import bank.rdmmesh.workflow.internal.service.WorkflowService;

/**
 * Дефолтный движок: прямой проход в {@link WorkflowService} (enum
 * {@code StateMachine}). Поведение пилота 1:1 — нулевой риск, выбирается
 * по умолчанию ({@code RDM_WORKFLOW_ENGINE=enum}).
 */
public final class EnumWorkflowEngine implements WorkflowEngine {

    private final WorkflowService service;

    public EnumWorkflowEngine(WorkflowService service) {
        this.service = service;
    }

    @Override
    public WorkflowTransitionEvent transition(
            UUID versionId, String targetStatus, UUID actor,
            Set<String> baseRoles, String comment) {
        return service.transition(versionId, targetStatus, actor, baseRoles, comment);
    }
}
