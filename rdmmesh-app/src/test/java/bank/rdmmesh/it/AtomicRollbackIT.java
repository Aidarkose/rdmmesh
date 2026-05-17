package bank.rdmmesh.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.api.port.OwnershipPort;
import bank.rdmmesh.api.port.VersionLifecyclePort;
import bank.rdmmesh.app.eventbus.SyncEventBus;
import bank.rdmmesh.authoring.AuthoringModule;
import bank.rdmmesh.catalog.CatalogModule;
import bank.rdmmesh.ownership.OwnershipModule;
import bank.rdmmesh.workflow.WorkflowModule;
import bank.rdmmesh.workflow.internal.service.WorkflowService;

/**
 * E14 round 14 (R9-backlog §3 #2, закрывает E14.5 §4) — атомарность
 * {@code WorkflowService.transition}: status-CAS (authoring) + journal
 * INSERT (workflow) + approval-task UPSERT (workflow) в ОДНОЙ Postgres tx.
 *
 * <p>Fault-injection: BEFORE INSERT триггер на
 * {@code workflow.workflow_transition} с {@code RAISE}. Ожидание —
 * {@code transition()} бросает, Postgres откатывает <b>всё</b>: статус
 * версии остаётся DRAFT, нет строки в journal, нет approval_task.
 * Контроль: без триггера тот же переход проходит полностью.
 *
 * <p>Локально {@code Skipped} (Docker-Desktop, E14.9 §2); реально гоняется
 * на CI (E14 round 14 CI-gating: push-job без {@code -DskipITs}).
 */
final class AtomicRollbackIT extends PostgresIT {

    private static WorkflowService workflow() {
        Jdbi jdbi = appJdbi();
        VersionLifecyclePort lifecycle = AuthoringModule.buildLifecyclePort(jdbi);
        OwnershipPort ownership = OwnershipModule.buildPort(jdbi);
        CatalogReadPort catalog = CatalogModule.buildReadPort(jdbi);
        EventBus bus = new SyncEventBus();
        return WorkflowModule.build(jdbi, lifecycle, ownership, catalog, bus).service();
    }

    /** Сеет domain→codeset→DRAFT-версию; created_by=actor (submit это разрешает). */
    private static UUID seedDraftVersion(UUID actor, String sfx) throws SQLException {
        UUID versionId = UUID.randomUUID();
        try (Connection c = adminConnection(); Statement st = c.createStatement()) {
            UUID domainId = UUID.randomUUID();
            UUID codesetId = UUID.randomUUID();
            st.execute("INSERT INTO catalog.domain (id, om_domain_id, name) VALUES ('"
                    + domainId + "', '" + UUID.randomUUID() + "', 'risk_" + sfx + "')");
            st.execute("INSERT INTO catalog.code_set "
                    + "(id, domain_id, name, key_spec, created_by) VALUES ('"
                    + codesetId + "', '" + domainId + "', 'cs_" + sfx
                    + "', '{}'::jsonb, '" + actor + "')");
            st.execute("INSERT INTO authoring.code_set_version "
                    + "(id, codeset_id, version, status, schema_version, created_by) VALUES ('"
                    + versionId + "', '" + codesetId + "', '0.1.0-draft', 'DRAFT', 1, '"
                    + actor + "')");
        }
        return versionId;
    }

    private static String status(UUID versionId) throws SQLException {
        try (Connection c = adminConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT status FROM authoring.code_set_version WHERE id='"
                                + versionId + "'")) {
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
    void journalFailureRollsBackStatusCasAndTask() throws SQLException {
        UUID actor = UUID.randomUUID();
        UUID v = seedDraftVersion(actor, "rb");
        WorkflowService ws = workflow();

        // Fault: любой INSERT в workflow.workflow_transition падает.
        try (Connection c = adminConnection(); Statement st = c.createStatement()) {
            st.execute("CREATE OR REPLACE FUNCTION workflow.it_fault() "
                    + "RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                    + "RAISE EXCEPTION 'IT injected fault'; END $$");
            st.execute("CREATE TRIGGER it_fault_trg BEFORE INSERT "
                    + "ON workflow.workflow_transition "
                    + "FOR EACH ROW EXECUTE FUNCTION workflow.it_fault()");
        }

        assertThatThrownBy(() ->
                        ws.transition(v, "IN_REVIEW", actor, Set.of("RDM_AUTHOR"), null))
                .isInstanceOf(RuntimeException.class);

        // Полный rollback: ни одна из трёх операций не зафиксирована.
        assertThat(status(v)).as("status-CAS откатан").isEqualTo("DRAFT");
        assertThat(count("SELECT count(*) FROM workflow.workflow_transition "
                + "WHERE version_id='" + v + "'")).isZero();
        assertThat(count("SELECT count(*) FROM workflow.approval_task "
                + "WHERE version_id='" + v + "'")).isZero();

        // Снять fault — контрольный happy-path: тот же переход проходит весь.
        try (Connection c = adminConnection(); Statement st = c.createStatement()) {
            st.execute("DROP TRIGGER it_fault_trg ON workflow.workflow_transition");
        }
        ws.transition(v, "IN_REVIEW", actor, Set.of("RDM_AUTHOR"), null);

        assertThat(status(v)).isEqualTo("IN_REVIEW");
        assertThat(count("SELECT count(*) FROM workflow.workflow_transition "
                + "WHERE version_id='" + v + "'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM workflow.approval_task "
                + "WHERE version_id='" + v + "' AND closed_at IS NULL")).isEqualTo(1);
    }
}
