package bank.rdmmesh.workflow;

import java.util.Optional;

import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.api.port.OwnershipPort;
import bank.rdmmesh.api.port.VersionLifecyclePort;
import bank.rdmmesh.api.port.WorkflowJournalPort;
import bank.rdmmesh.api.port.WorkflowPort;
import bank.rdmmesh.workflow.internal.PostgresWorkflowJournalPort;
import bank.rdmmesh.workflow.internal.PostgresWorkflowPort;
import bank.rdmmesh.workflow.internal.engine.EnumWorkflowEngine;
import bank.rdmmesh.workflow.internal.engine.FlowableEngineManager;
import bank.rdmmesh.workflow.internal.engine.FlowableWorkflowEngine;
import bank.rdmmesh.workflow.internal.engine.WorkflowEngine;
import bank.rdmmesh.workflow.internal.engine.WorkflowTemplateService;
import bank.rdmmesh.workflow.internal.service.WorkflowService;
import bank.rdmmesh.workflow.resource.MyTasksResource;
import bank.rdmmesh.workflow.resource.WorkflowTemplateResource;
import bank.rdmmesh.workflow.resource.WorkflowTransitionResource;

/**
 * Composition factory для {@code rdmmesh-workflow}. Возвращает контейнер с двумя
 * REST-resource'ами и реализацией {@link WorkflowPort}; composition root
 * регистрирует resource'ы в Jersey, порт прокидывает в audit/publishing.
 *
 * <p>V2 / BR-18 (ADR-009): движок переходов выбирается {@link EngineKind} —
 * {@link EngineKind#ENUM} (дефолт, enum-StateMachine, поведение пилота 1:1)
 * либо {@link EngineKind#FLOWABLE} (in-process BPMN). Бизнес-логика и аудит
 * в обоих случаях — один и тот же {@link WorkflowService} (без рефакторинга,
 * SPEC ADR-004 / §5.2).
 */
public final class WorkflowModule {

    private WorkflowModule() {}

    /** Какой движок приводит в действие переходы. */
    public enum EngineKind { ENUM, FLOWABLE }

    /** JDBC-координаты для собственного пула Flowable (только при FLOWABLE). */
    public record FlowableDbConfig(String jdbcUrl, String user, String password) {}

    /**
     * Дефолт: enum-движок, без справочника согласующих (обратная
     * совместимость — submit без assignee = legacy broadcast). Используется
     * ITs (AtomicRollbackIT/FlowableWorkflowIT), которые вызывают service
     * напрямую и не задают assignee.
     */
    public static Resources build(
            Jdbi jdbi,
            VersionLifecyclePort lifecycle,
            OwnershipPort ownership,
            CatalogReadPort catalog,
            EventBus eventBus) {
        return build(jdbi, lifecycle, ownership, catalog, eventBus,
                null, EngineKind.ENUM, null);
    }

    /**
     * Выбор движка + справочник согласующих (E17, BR-21). {@code flowableDb}
     * обязателен только при {@link EngineKind#FLOWABLE}; {@code approverDirectory}
     * может быть {@code null} — тогда адресная маршрутизация submit'а отключена
     * (legacy broadcast по rdm_asset_ownership).
     */
    public static Resources build(
            Jdbi jdbi,
            VersionLifecyclePort lifecycle,
            OwnershipPort ownership,
            CatalogReadPort catalog,
            EventBus eventBus,
            bank.rdmmesh.api.port.ApproverDirectoryPort approverDirectory,
            EngineKind kind,
            FlowableDbConfig flowableDb) {
        return build(jdbi, lifecycle, ownership, catalog, eventBus,
                approverDirectory, null, kind, flowableDb);
    }

    /**
     * Stage 7: тот же build + {@code referenceIntegrity} — жёсткий гейт ссылочной
     * целостности на submit. {@code null} — гейт выключен.
     */
    public static Resources build(
            Jdbi jdbi,
            VersionLifecyclePort lifecycle,
            OwnershipPort ownership,
            CatalogReadPort catalog,
            EventBus eventBus,
            bank.rdmmesh.api.port.ApproverDirectoryPort approverDirectory,
            bank.rdmmesh.api.port.ReferenceIntegrityPort referenceIntegrity,
            EngineKind kind,
            FlowableDbConfig flowableDb) {

        WorkflowService service =
                new WorkflowService(jdbi, lifecycle, ownership, catalog, eventBus,
                        approverDirectory, referenceIntegrity);

        WorkflowEngine engine;
        FlowableEngineManager manager = null;
        WorkflowTemplateResource templateResource = null;
        if (kind == EngineKind.FLOWABLE) {
            if (flowableDb == null) {
                throw new IllegalArgumentException(
                        "EngineKind.FLOWABLE требует FlowableDbConfig");
            }
            manager = new FlowableEngineManager(
                    flowableDb.jdbcUrl(), flowableDb.user(), flowableDb.password(),
                    service);
            engine = new FlowableWorkflowEngine(manager, service, jdbi, lifecycle, catalog);
            // V2 / BR-18 round 2: per-domain BPMN-шаблоны (только при Flowable).
            templateResource = new WorkflowTemplateResource(
                    new WorkflowTemplateService(jdbi, manager));
        } else {
            engine = new EnumWorkflowEngine(service);
        }

        return new Resources(
                service,
                new PostgresWorkflowPort(service),
                new WorkflowTransitionResource(engine, service),
                new MyTasksResource(service),
                Optional.ofNullable(manager),
                Optional.ofNullable(templateResource));
    }

    /** Тонкий append-only порт для системных post-hoc transitions (publish/deprecate из E6). */
    public static WorkflowJournalPort buildJournalPort(Jdbi jdbi) {
        return new PostgresWorkflowJournalPort(jdbi);
    }

    /**
     * @param engineManager присутствует только при {@link EngineKind#FLOWABLE} —
     *                      composition root регистрирует его как Dropwizard
     *                      {@code Managed} (close движка на остановке).
     */
    public record Resources(
            WorkflowService service,
            WorkflowPort port,
            WorkflowTransitionResource transitions,
            MyTasksResource myTasks,
            Optional<FlowableEngineManager> engineManager,
            Optional<WorkflowTemplateResource> templates) {}
}
