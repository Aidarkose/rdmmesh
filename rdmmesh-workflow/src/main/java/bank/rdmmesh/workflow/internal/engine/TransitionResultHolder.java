package bank.rdmmesh.workflow.internal.engine;

import bank.rdmmesh.spec.events.WorkflowTransitionEvent;

/**
 * Thread-local передача результата {@code WorkflowService.transition} из
 * {@link WorkflowTransitionDelegate} (исполняется внутри
 * {@code RuntimeService.trigger}) обратно в {@link FlowableWorkflowEngine}.
 *
 * <p>Корректно, потому что Flowable сконфигурирован <b>синхронно</b>
 * ({@code asyncExecutorActivate=false}): {@code trigger()} прогоняет
 * service-task в <i>том же потоке</i> и той же команде. Set/get/clear
 * обрамляются try/finally вокруг одного {@code trigger()}.
 */
final class TransitionResultHolder {

    private static final ThreadLocal<WorkflowTransitionEvent> RESULT = new ThreadLocal<>();

    private TransitionResultHolder() {}

    static void set(WorkflowTransitionEvent event) {
        RESULT.set(event);
    }

    static WorkflowTransitionEvent get() {
        return RESULT.get();
    }

    static void clear() {
        RESULT.remove();
    }
}
