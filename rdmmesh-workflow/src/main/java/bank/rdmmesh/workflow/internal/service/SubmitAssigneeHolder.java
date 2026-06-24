package bank.rdmmesh.workflow.internal.service;

import java.util.UUID;

/**
 * Переносит выбранного Author'ом согласующего (BR-21, handoff E17) от
 * REST-resource'а к {@link WorkflowService} без изменения сигнатуры
 * {@code WorkflowEngine.transition} (а значит и без правки Flowable-пути).
 *
 * <p>ThreadLocal корректен: единственная точка входа — REST-resource;
 * вызов синхронный (для Flowable {@code RuntimeService.trigger} тоже
 * синхронный — async выключен, см. E16 §0), тот же поток до
 * {@code WorkflowService.transition}. Resource обязан {@link #clear()}
 * в {@code finally}.
 */
public final class SubmitAssigneeHolder {

    /** Выбор Author'а при submit'е: домен + steward-учётка + owner-учётка. */
    public record Assignee(UUID domainId, UUID stewardUserId, UUID ownerUserId) {}

    private static final ThreadLocal<Assignee> TL = new ThreadLocal<>();

    private SubmitAssigneeHolder() {}

    public static void set(Assignee a) {
        TL.set(a);
    }

    public static Assignee get() {
        return TL.get();
    }

    public static void clear() {
        TL.remove();
    }
}
