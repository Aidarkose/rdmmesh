package bank.rdmmesh.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;

import org.flowable.engine.runtime.ProcessInstance;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.api.port.OwnershipPort;
import bank.rdmmesh.api.port.VersionLifecyclePort;
import bank.rdmmesh.api.port.WorkflowPort;
import bank.rdmmesh.app.eventbus.SyncEventBus;
import bank.rdmmesh.authoring.AuthoringModule;
import bank.rdmmesh.catalog.CatalogModule;
import bank.rdmmesh.ownership.OwnershipModule;
import bank.rdmmesh.workflow.WorkflowModule;
import bank.rdmmesh.workflow.internal.dao.WorkflowTemplateDao;
import bank.rdmmesh.workflow.internal.engine.FlowableEngineManager;
import bank.rdmmesh.workflow.internal.engine.FlowableWorkflowEngine;
import bank.rdmmesh.workflow.internal.engine.WorkflowTemplateService;
import bank.rdmmesh.workflow.internal.service.WorkflowService;

/**
 * V2 / BR-18 — Flowable реально приводит в действие 4-eyes (round 1) и
 * per-domain BPMN-шаблоны (round 2, модель A: кастомная топология НЕ
 * обходит guard'ы — каждый переход через WorkflowService+enum-StateMachine).
 *
 * <p>Flowable создаёт свои таблицы (ACT_, FLW_) сам
 * ({@code databaseSchemaUpdate}) в схеме {@code workflow_engine} (Flyway
 * V031/V032). Локально {@code Skipped}
 * (Docker-Desktop, E14.9 §2); авторитетный прогон — CI.
 */
final class FlowableWorkflowIT extends PostgresIT {

    /** Кастомный per-domain BPMN: иной process-id, те же якоря контракта. */
    private static final String CUSTOM_BPMN = """
        <?xml version="1.0" encoding="UTF-8"?>
        <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                     xmlns:flowable="http://flowable.org/bpmn"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     targetNamespace="http://rdmmesh.bank/wf">
          <process id="custom_dom" name="custom domain 4-eyes" isExecutable="true">
            <startEvent id="s"/>
            <sequenceFlow id="f0" sourceRef="s" targetRef="rt_await"/>
            <receiveTask id="rt_await" name="await"/>
            <sequenceFlow id="f1" sourceRef="rt_await" targetRef="svc"/>
            <serviceTask id="svc" name="apply"
                         flowable:delegateExpression="${rdmTransitionDelegate}"/>
            <sequenceFlow id="f2" sourceRef="svc" targetRef="gw"/>
            <exclusiveGateway id="gw" default="f_loop"/>
            <sequenceFlow id="f_end" sourceRef="gw" targetRef="e">
              <conditionExpression xsi:type="tFormalExpression">${terminal == true}</conditionExpression>
            </sequenceFlow>
            <sequenceFlow id="f_loop" sourceRef="gw" targetRef="rt_await"/>
            <endEvent id="e"/>
          </process>
        </definitions>
        """;

    private record Ctx(Jdbi jdbi, WorkflowService service,
                       FlowableEngineManager manager, FlowableWorkflowEngine engine) {}

    private static Ctx buildCtx() {
        Jdbi jdbi = appJdbi();
        VersionLifecyclePort lifecycle = AuthoringModule.buildLifecyclePort(jdbi);
        OwnershipPort ownership = OwnershipModule.buildPort(jdbi);
        CatalogReadPort catalog = CatalogModule.buildReadPort(jdbi);
        EventBus bus = new SyncEventBus();
        WorkflowService service =
                WorkflowModule.build(jdbi, lifecycle, ownership, catalog, bus).service();
        FlowableEngineManager manager = new FlowableEngineManager(
                appJdbcUrl(), appUser(), appPassword(), service);
        FlowableWorkflowEngine engine =
                new FlowableWorkflowEngine(manager, service, jdbi, lifecycle, catalog);
        return new Ctx(jdbi, service, manager, engine);
    }

    private record Seed(UUID versionId, UUID domainId) {}

    private static Seed seedDraft(UUID author, String sfx) throws SQLException {
        UUID versionId = UUID.randomUUID();
        UUID domainId = UUID.randomUUID();
        try (Connection c = adminConnection(); Statement st = c.createStatement()) {
            UUID codesetId = UUID.randomUUID();
            st.execute("INSERT INTO catalog.domain (id, om_domain_id, name) VALUES ('"
                    + domainId + "', '" + UUID.randomUUID() + "', 'risk_" + sfx + "')");
            st.execute("INSERT INTO catalog.code_set "
                    + "(id, domain_id, name, key_spec, created_by) VALUES ('"
                    + codesetId + "', '" + domainId + "', 'cs_" + sfx
                    + "', '{}'::jsonb, '" + author + "')");
            st.execute("INSERT INTO authoring.code_set_version "
                    + "(id, codeset_id, version, status, schema_version, created_by) VALUES ('"
                    + versionId + "', '" + codesetId + "', '0.1.0-draft', 'DRAFT', 1, '"
                    + author + "')");
        }
        return new Seed(versionId, domainId);
    }

    private static String status(UUID v) throws SQLException {
        try (Connection c = adminConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT status FROM authoring.code_set_version WHERE id='" + v + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = adminConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void flowableEngineDrivesFullFourEyesAndKeepsInvariants() throws SQLException {
        UUID author = UUID.randomUUID();
        UUID steward = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        Seed s = seedDraft(author, "flw");
        UUID v = s.versionId();

        Ctx ctx = buildCtx();
        try {
            FlowableWorkflowEngine engine = ctx.engine();

            engine.transition(v, "IN_REVIEW", author, Set.of("RDM_AUTHOR"), null);
            assertThat(status(v)).isEqualTo("IN_REVIEW");

            // Self-approval: автор пробует steward_approve → StateMachine
            // бросает ДО role-gate; Flowable откатывает trigger, статус цел.
            assertThatThrownBy(() -> engine.transition(
                            v, "STEWARD_APPROVED", author, Set.of("RDM_STEWARD"), null))
                    .isInstanceOf(WorkflowPort.SelfApprovalException.class);
            assertThat(status(v)).isEqualTo("IN_REVIEW");

            engine.transition(v, "STEWARD_APPROVED", steward, Set.of("RDM_STEWARD"), null);
            assertThat(status(v)).isEqualTo("STEWARD_APPROVED");
            engine.transition(v, "OWNER_APPROVED", owner, Set.of("RDM_OWNER"), null);
            assertThat(status(v)).isEqualTo("OWNER_APPROVED");

            assertThat(count("SELECT count(*) FROM workflow.workflow_transition "
                    + "WHERE version_id='" + v + "'")).isEqualTo(3);
            assertThat(count("SELECT count(*) FROM authoring.code_set_version_reviewer "
                    + "WHERE version_id='" + v + "' AND om_user_id='" + steward + "'"))
                    .isEqualTo(1);
        } finally {
            ctx.manager().stop();
        }
    }

    @Test
    void orphanInstanceCancelledOnVersionDelete() throws SQLException {
        UUID author = UUID.randomUUID();
        UUID v = seedDraft(author, "orph").versionId();
        Ctx ctx = buildCtx();
        try {
            ctx.engine().transition(v, "IN_REVIEW", author, Set.of("RDM_AUTHOR"), null);
            assertThat(ctx.manager().runtimeService().createProcessInstanceQuery()
                    .processInstanceBusinessKey(v.toString()).count())
                    .as("инстанс поднят").isEqualTo(1);

            // Эмуляция подписчика VersionDeletedDomainEvent (E16.3 cleanup).
            ctx.manager().cancelForVersion(v);
            assertThat(ctx.manager().runtimeService().createProcessInstanceQuery()
                    .processInstanceBusinessKey(v.toString()).count())
                    .as("осиротевший инстанс погашен").isZero();

            ctx.manager().cancelForVersion(v); // идемпотентно — без исключений
        } finally {
            ctx.manager().stop();
        }
    }

    @Test
    void perDomainTemplateDrivesTransitionsAndStaysNoBypass() throws SQLException {
        UUID admin = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID steward = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        Seed s = seedDraft(author, "tpl");
        UUID v = s.versionId();
        UUID domain = s.domainId();

        Ctx ctx = buildCtx();
        try {
            WorkflowTemplateService templates =
                    new WorkflowTemplateService(ctx.jdbi(), ctx.manager());

            // Деплой кастомного per-domain BPMN (RDM_ADMIN).
            WorkflowTemplateService.DeployResult dr = templates.deploy(
                    domain, CUSTOM_BPMN.getBytes(StandardCharsets.UTF_8), admin);
            assertThat(dr.processKey()).isEqualTo("custom_dom");
            assertThat(dr.version()).isEqualTo(1);
            assertThat(dr.sha256()).hasSize(64);

            // Реестр (V032) — append-only-аудит.
            WorkflowTemplateDao.TemplateRow row = ctx.jdbi().withExtension(
                    WorkflowTemplateDao.class, d -> d.findActiveByDomain(domain))
                    .orElseThrow();
            assertThat(row.processKey()).isEqualTo("custom_dom");
            assertThat(row.deployedBy()).isEqualTo(admin);
            assertThat(row.active()).isTrue();

            // submit → инстанс должен подняться из tenant-процесса домена.
            ctx.engine().transition(v, "IN_REVIEW", author, Set.of("RDM_AUTHOR"), null);
            assertThat(status(v)).isEqualTo("IN_REVIEW");

            ProcessInstance pi = ctx.manager().runtimeService()
                    .createProcessInstanceQuery()
                    .processInstanceBusinessKey(v.toString())
                    .singleResult();
            assertThat(pi).as("инстанс существует (ещё не терминал)").isNotNull();
            assertThat(pi.getTenantId())
                    .as("поднят per-domain tenant-процесс").isEqualTo(domain.toString());
            assertThat(pi.getProcessDefinitionId())
                    .as("именно кастомный процесс домена").startsWith("custom_dom:");

            // No-bypass сохранён даже с кастомной топологией: self-approval 409.
            assertThatThrownBy(() -> ctx.engine().transition(
                            v, "STEWARD_APPROVED", author, Set.of("RDM_STEWARD"), null))
                    .isInstanceOf(WorkflowPort.SelfApprovalException.class);

            ctx.engine().transition(v, "STEWARD_APPROVED", steward, Set.of("RDM_STEWARD"), null);
            ctx.engine().transition(v, "OWNER_APPROVED", owner, Set.of("RDM_OWNER"), null);
            assertThat(status(v)).isEqualTo("OWNER_APPROVED");
            assertThat(count("SELECT count(*) FROM workflow.workflow_transition "
                    + "WHERE version_id='" + v + "'")).isEqualTo(3);
        } finally {
            ctx.manager().stop();
        }
    }
}
