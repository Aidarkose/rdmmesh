package bank.rdmmesh.workflow.internal.engine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.workflow.internal.dao.WorkflowTemplateDao;
import bank.rdmmesh.workflow.internal.dao.WorkflowTemplateDao.TemplateRow;

/**
 * Оркестрация per-domain BPMN-шаблонов (V2 / BR-18 round 2, ADR-0009
 * модель A + Flowable-tenancy). Деплоит BPMN в Flowable
 * ({@code tenantId=domain}) и ведёт append-only-реестр (V032) — кто/когда/
 * какой sha/версию активировал (воспроизводимость, SPEC §2.3).
 *
 * <p>Порядок: валидация контракта + Flowable-деплой
 * ({@link FlowableEngineManager#deployForDomain}) → meta-tx
 * (deactivate prev + insert new active). Если meta-tx упадёт — в Flowable
 * остаётся неиспользуемый tenant-деплой (нет active-строки → движок берёт
 * дефолт); это не ломает корректность, лог предупреждает.
 */
public final class WorkflowTemplateService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTemplateService.class);

    private final Jdbi jdbi;
    private final FlowableEngineManager manager;

    public WorkflowTemplateService(Jdbi jdbi, FlowableEngineManager manager) {
        this.jdbi = jdbi;
        this.manager = manager;
    }

    public record DeployResult(
            UUID domainId, String processKey, int version,
            String sha256, String flowableDeploymentId) {}

    /**
     * @throws IllegalArgumentException если BPMN не проходит контракт
     *         ({@link BpmnTemplateValidator}) — resource → 400.
     */
    public DeployResult deploy(UUID domainId, byte[] bpmnXml, UUID deployedBy) {
        String sha = BpmnTemplateValidator.sha256Hex(bpmnXml);
        // ADR-0010 B2: контракт + per-domain граф + WorkflowGraphInvariants
        // (deploy-time compliance-gate) ДО Flowable-деплоя — невалидный
        // граф → IllegalArgumentException (400), ничего не задеплоено.
        BpmnTemplateValidator.Contract c = BpmnTemplateValidator.validate(bpmnXml);
        String graphJson = c.graphJson().orElse(null);
        FlowableEngineManager.DomainDeployment dep =
                manager.deployForDomain(domainId, bpmnXml); // деплой (re-validate idem.)
        int version = jdbi.inTransaction(h -> {
            WorkflowTemplateDao dao = h.attach(WorkflowTemplateDao.class);
            dao.deactivateActive(domainId);
            int v = dao.nextVersion(domainId);
            dao.insert(domainId, dep.processKey(), dep.flowableDeploymentId(),
                    sha, v, deployedBy, graphJson);
            return v;
        });
        log.info("workflow-template: domain={} key={} v{} sha={} by={}",
                domainId, dep.processKey(), version, sha, deployedBy);
        return new DeployResult(domainId, dep.processKey(), version, sha,
                dep.flowableDeploymentId());
    }

    public List<TemplateRow> listAll() {
        return jdbi.withExtension(WorkflowTemplateDao.class, WorkflowTemplateDao::listAll);
    }

    public Optional<TemplateRow> active(UUID domainId) {
        return jdbi.withExtension(WorkflowTemplateDao.class,
                d -> d.findActiveByDomain(domainId));
    }

    /**
     * Откат домена к дефолтному {@code rdm4eyes}: снимаем active с текущей
     * строки (Flowable tenant-деплой остаётся, но движок без active-строки
     * берёт дефолт). @return true если был активный шаблон.
     */
    public boolean revertToDefault(UUID domainId) {
        int n = jdbi.withExtension(WorkflowTemplateDao.class,
                d -> d.deactivateActive(domainId));
        log.info("workflow-template: domain={} reverted to default (rows={})", domainId, n);
        return n > 0;
    }
}
