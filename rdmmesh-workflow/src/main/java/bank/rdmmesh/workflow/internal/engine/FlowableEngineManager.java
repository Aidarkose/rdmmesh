package bank.rdmmesh.workflow.internal.engine;

import java.util.Map;
import java.util.UUID;

import org.flowable.engine.ProcessEngine;
import org.flowable.engine.ProcessEngineConfiguration;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.flowable.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.flowable.engine.repository.Deployment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.workflow.internal.service.WorkflowService;
import io.dropwizard.lifecycle.Managed;

/**
 * Строит и держит in-process {@link ProcessEngine} Flowable (V2 / BR-18,
 * ADR-009). Dropwizard {@link Managed} — закрывается при остановке сервиса.
 *
 * <p><b>Lean (SPEC §3.1).</b> Тот же Postgres — НЕ новый внешний компонент.
 * Flowable сам управляет своими ~25 ACT_*-таблицами
 * ({@code databaseSchemaUpdate=true}) в изолированной схеме
 * {@code workflow_engine} (создаётся Flyway-миграцией V031; carve-out из
 * инварианта «Flyway — единственный DDL» — задокументирован в ADR-009).
 * Собственный JDBC-пул Flowable (передаём url/user/pass, а не Dropwizard
 * ManagedDataSource) — нет переплетения lifecycle'ов; {@code currentSchema}
 * в URL + {@code databaseSchema} = двойная страховка изоляции на Postgres.
 *
 * <p>Async-executor выключен (нет таймеров/async-job'ов в дефолтном 4-eyes →
 * меньше потоков, синхронный {@code trigger} — корректность
 * {@link TransitionResultHolder}). История — {@code none}: журнал
 * переходов ведёт {@code workflow.workflow_transition} (audit source of
 * truth, SPEC §3.8), дублировать в ACT_HI_* не нужно.
 *
 * <p>Движок строится сразу в конструкторе (Flyway уже отработал к моменту
 * {@code WorkflowModule.build}, схема готова), {@link #start()} — no-op;
 * {@link #stop()} закрывает движок и его пул.
 */
public final class FlowableEngineManager implements Managed {

    private static final Logger log = LoggerFactory.getLogger(FlowableEngineManager.class);

    private static final String SCHEMA = "workflow_engine";
    private static final String BPMN_RESOURCE = "processes/rdm-4eyes.bpmn20.xml";

    /** Ключ процесса в {@code rdm-4eyes.bpmn20.xml}. */
    public static final String PROCESS_KEY = "rdm4eyes";
    /** Activity-id receive-task'а (точка ожидания transition'а). */
    public static final String AWAIT_ACTIVITY = "rt_await";

    private final ProcessEngine engine;

    public FlowableEngineManager(String jdbcUrl, String user, String pass,
                                 WorkflowService workflowService) {
        ProcessEngineConfiguration cfg = ProcessEngineConfiguration
                .createStandaloneProcessEngineConfiguration()
                .setJdbcUrl(withCurrentSchema(jdbcUrl))
                .setJdbcUsername(user)
                .setJdbcPassword(pass)
                .setJdbcDriver("org.postgresql.Driver")
                .setDatabaseSchema(SCHEMA)
                .setDatabaseSchemaUpdate(ProcessEngineConfiguration.DB_SCHEMA_UPDATE_TRUE)
                .setAsyncExecutorActivate(false)
                .setHistory("none");
        ProcessEngineConfigurationImpl impl = (ProcessEngineConfigurationImpl) cfg;
        // Делегат как bean → BPMN ${rdmTransitionDelegate}.
        impl.setBeans(Map.of("rdmTransitionDelegate",
                new WorkflowTransitionDelegate(workflowService)));
        // Нужен ТОЛЬКО core BPMN. flowable-engine транзитивно тянет
        // event-registry + IDM sub-движки; они авто-активируются и гоняют
        // СВОИ Liquibase-changelog'и. В shaded uber-jar (CI/prod-деплой)
        // changelog event-registry дублируется по одному classpath-пути
        // (несколько flowable-jar'ов) → Liquibase «Found 2 files…» →
        // движок не стартует. Нам эти sub-движки не нужны вовсе —
        // выключаем: и фикс дубликата, и lean (меньше ACT_*/FLW_*-таблиц).
        impl.setDisableIdmEngine(true);
        impl.setDisableEventRegistry(true);

        this.engine = cfg.buildProcessEngine();

        RepositoryService repo = engine.getRepositoryService();
        repo.createDeployment()
                .name("rdm-default-4eyes")
                .addClasspathResource(BPMN_RESOURCE)
                .enableDuplicateFiltering() // повторный старт не плодит deployment'ы
                .deploy();
        log.info("Flowable engine ready: schema={}, process={}, history=none, async=off",
                SCHEMA, PROCESS_KEY);
    }

    public RuntimeService runtimeService() {
        return engine.getRuntimeService();
    }

    /** Результат деплоя per-domain BPMN (V2 / BR-18 round 2). */
    public record DomainDeployment(String flowableDeploymentId, String processKey) {}

    /**
     * Деплоит per-domain BPMN в Flowable с {@code tenantId=domainId}
     * (нативная мульти-аренда). Контракт шаблона проверяется ДО деплоя
     * ({@link BpmnTemplateValidator}); невалидный → IllegalArgumentException
     * (resource → 400), в Flowable ничего не попадает.
     */
    public DomainDeployment deployForDomain(UUID domainId, byte[] bpmnXml) {
        BpmnTemplateValidator.Contract c = BpmnTemplateValidator.validate(bpmnXml);
        Deployment d = engine.getRepositoryService().createDeployment()
                .name("rdm-domain-" + domainId)
                .tenantId(domainId.toString())
                .addBytes(c.processKey() + ".bpmn20.xml", bpmnXml)
                .deploy();
        log.info("Flowable: per-domain template deployed domain={} key={} deployment={}",
                domainId, c.processKey(), d.getId());
        return new DomainDeployment(d.getId(), c.processKey());
    }

    /**
     * E16.3 round-3 cleanup: гасит осиротевшие инстансы версии (businessKey
     * = versionId) после удаления DRAFT'а. Идемпотентно (нет инстансов —
     * no-op), best-effort: исключение логируется и НЕ пробрасывается —
     * подписчик event-bus'а не должен влиять на удаление версии.
     */
    public void cancelForVersion(UUID versionId) {
        String bk = versionId.toString();
        try {
            RuntimeService rt = engine.getRuntimeService();
            var instances = rt.createProcessInstanceQuery()
                    .processInstanceBusinessKey(bk)
                    .list();
            for (var pi : instances) {
                rt.deleteProcessInstance(pi.getId(), "rdm: DRAFT version deleted");
            }
            if (!instances.isEmpty()) {
                log.info("Flowable: cancelled {} orphan instance(s) for version={}",
                        instances.size(), bk);
            }
        } catch (RuntimeException e) {
            log.warn("Flowable: cancelForVersion({}) failed (best-effort): {}",
                    bk, e.toString());
        }
    }

    /** Есть ли в Flowable определение {@code processKey} для tenant=domainId. */
    public boolean hasTenantProcess(String processKey, UUID domainId) {
        return engine.getRepositoryService().createProcessDefinitionQuery()
                .processDefinitionKey(processKey)
                .processDefinitionTenantId(domainId.toString())
                .count() > 0;
    }

    @Override
    public void start() {
        // Движок уже построен в конструкторе (Flyway отработал раньше).
    }

    @Override
    public void stop() {
        log.info("Flowable engine: closing");
        engine.close();
    }

    /** Гарантирует search_path=workflow_engine у соединений пула Flowable. */
    private static String withCurrentSchema(String url) {
        if (url.contains("currentSchema=")) {
            return url;
        }
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA;
    }
}
