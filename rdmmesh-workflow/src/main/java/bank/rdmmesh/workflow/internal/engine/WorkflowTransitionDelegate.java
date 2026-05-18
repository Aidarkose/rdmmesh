package bank.rdmmesh.workflow.internal.engine;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.flowable.engine.delegate.DelegateExecution;
import org.flowable.engine.delegate.JavaDelegate;

import bank.rdmmesh.spec.events.WorkflowTransitionEvent;
import bank.rdmmesh.workflow.internal.service.WorkflowService;

/**
 * BPMN service-task: единственное место, где Flowable-процесс вызывает
 * <b>существующий</b> {@code WorkflowService.transition} — вся валидация
 * (self-approval / role-gate / no-bypass) и атомарные side-эффекты
 * (CAS+journal+approval-task+event) остаются в аудированном Java-коде, BPMN
 * лишь оркеструет (ADR-009; SPEC ADR-004 «без рефакторинга бизнес-логики»).
 *
 * <p>Зарегистрирован как bean {@code rdmTransitionDelegate} в
 * {@code ProcessEngineConfiguration.beans}; BPMN ссылается на него через
 * {@code flowable:delegateExpression="${rdmTransitionDelegate}"}.
 *
 * <p>Переменные процесса (ставит {@link FlowableWorkflowEngine} перед
 * {@code trigger}): {@code versionId}, {@code targetStatus}, {@code actor},
 * {@code baseRoles} (CSV), {@code comment}. Результат — в
 * {@link TransitionResultHolder}; флаг {@code terminal} (для шлюза «конец vs
 * петля») = достигнут ли OWNER_APPROVED.
 *
 * <p><b>Ошибки.</b> {@code WorkflowService} бросает
 * {@code WorkflowPort.SelfApproval/IllegalStateTransition/InsufficientRole}
 * либо {@code IllegalArgumentException} — исключение прокидывается наружу,
 * Flowable откатывает команду {@code trigger} (токен остаётся на
 * receive-task'е — переход можно повторить), {@code WorkflowService} ничего
 * не закоммитил. {@link FlowableWorkflowEngine} разворачивает причину и
 * перебрасывает оригинал, чтобы REST вернул тот же код, что и enum-движок.
 */
public final class WorkflowTransitionDelegate implements JavaDelegate {

    private final WorkflowService service;

    public WorkflowTransitionDelegate(WorkflowService service) {
        this.service = service;
    }

    @Override
    public void execute(DelegateExecution execution) {
        UUID versionId = UUID.fromString((String) execution.getVariable("versionId"));
        String target = (String) execution.getVariable("targetStatus");
        UUID actor = UUID.fromString((String) execution.getVariable("actor"));
        String comment = (String) execution.getVariable("comment");
        Set<String> baseRoles = parseRoles((String) execution.getVariable("baseRoles"));

        // Бросок отсюда → Flowable rollback команды trigger; WorkflowService
        // сам ничего не закоммитил (исключение до/внутри его tx).
        WorkflowTransitionEvent event =
                service.transition(versionId, target, actor, baseRoles, comment);

        TransitionResultHolder.set(event);
        // Терминал процесса = достигнут OWNER_APPROVED (дальше publish/deprecate —
        // системный путь вне BPMN, через WorkflowJournalPort, без изменений).
        execution.setVariable("terminal", "OWNER_APPROVED".equals(target));
    }

    private static Set<String> parseRoles(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return new LinkedHashSet<>(Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList());
    }
}
