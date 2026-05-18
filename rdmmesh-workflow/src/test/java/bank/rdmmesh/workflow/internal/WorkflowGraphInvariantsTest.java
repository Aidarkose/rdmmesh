package bank.rdmmesh.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import bank.rdmmesh.api.port.WorkflowPort;
import bank.rdmmesh.workflow.internal.StateMachine.Action;
import bank.rdmmesh.workflow.internal.StateMachine.Request;
import bank.rdmmesh.workflow.internal.StateMachine.Status;
import bank.rdmmesh.workflow.internal.WorkflowGraph.Kind;

/**
 * ADR-0010 вариант B — статическая compliance-сеть для произвольных
 * графов + graph-driven runtime guard. Доказывает, что «no-bypass»
 * сохраняется для ЛЮБОГО прошедшего {@link WorkflowGraphInvariants} графа,
 * и что плохие графы (обход steward/owner) отвергаются. Чистый unit.
 */
final class WorkflowGraphInvariantsTest {

    // ── compliance-сеть ─────────────────────────────────────────────────────────

    @Test
    void defaultFourEyesIsCompliant() {
        assertThat(WorkflowGraphInvariants.isValid(WorkflowGraph.defaultFourEyes()))
                .isTrue();
    }

    @Test
    void directOwnerEdgeSkippingStewardIsRejected() {
        WorkflowGraph bad = WorkflowGraph.builder()
                .edge(Status.DRAFT, Status.OWNER_APPROVED,
                        Action.owner_approve, Kind.OWNER, false, false, true)
                .build();
        assertThatThrownBy(() -> WorkflowGraphInvariants.validate(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STEWARD");
    }

    @Test
    void fakeStewardOfWrongKindIsRejected() {
        // «steward»-ступень смоделирована как SUBMIT — обход 4-eyes.
        WorkflowGraph bad = WorkflowGraph.builder()
                .edge(Status.DRAFT, Status.IN_REVIEW,
                        Action.submit, Kind.SUBMIT, false, false, false)
                .edge(Status.IN_REVIEW, Status.STEWARD_APPROVED,
                        Action.submit, Kind.SUBMIT, false, false, false)
                .edge(Status.STEWARD_APPROVED, Status.OWNER_APPROVED,
                        Action.owner_approve, Kind.OWNER, false, false, true)
                .build();
        assertThatThrownBy(() -> WorkflowGraphInvariants.validate(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("STEWARD");
    }

    @Test
    void submitEdgeIntoTerminalIsRejected() {
        WorkflowGraph bad = WorkflowGraph.builder()
                .edge(Status.DRAFT, Status.OWNER_APPROVED,
                        Action.submit, Kind.SUBMIT, false, false, false)
                .build();
        assertThatThrownBy(() -> WorkflowGraphInvariants.validate(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(Status.OWNER_APPROVED.name());
    }

    @Test
    void systemRejectEdgeIsRejected() {
        WorkflowGraph bad = WorkflowGraph.builder()
                .edge(Status.DRAFT, Status.IN_REVIEW,
                        Action.submit, Kind.SUBMIT, false, false, false)
                .edge(Status.IN_REVIEW, Status.STEWARD_APPROVED,
                        Action.steward_approve, Kind.STEWARD, false, true, false)
                .edge(Status.STEWARD_APPROVED, Status.OWNER_APPROVED,
                        Action.owner_approve, Kind.OWNER, false, false, true)
                .edge(Status.IN_REVIEW, Status.DRAFT,
                        Action.deprecate, Kind.SYSTEM, true, false, false)
                .build();
        assertThatThrownBy(() -> WorkflowGraphInvariants.validate(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reject");
    }

    @Test
    void unreachableTerminalIsRejected() {
        WorkflowGraph bad = WorkflowGraph.builder()
                .edge(Status.DRAFT, Status.IN_REVIEW,
                        Action.submit, Kind.SUBMIT, false, false, false)
                .build();
        assertThatThrownBy(() -> WorkflowGraphInvariants.validate(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("недостижим");
    }

    @Test
    void customButCompliantGraphPasses() {
        // Кастом: без owner_reject/publish-рёбер, но steward→owner-цепочка цела.
        WorkflowGraph ok = WorkflowGraph.builder()
                .edge(Status.DRAFT, Status.IN_REVIEW,
                        Action.submit, Kind.SUBMIT, false, false, false)
                .edge(Status.IN_REVIEW, Status.STEWARD_APPROVED,
                        Action.steward_approve, Kind.STEWARD, false, true, false)
                .edge(Status.IN_REVIEW, Status.DRAFT,
                        Action.steward_reject, Kind.STEWARD, true, false, false)
                .edge(Status.STEWARD_APPROVED, Status.OWNER_APPROVED,
                        Action.owner_approve, Kind.OWNER, false, false, true)
                .build();
        assertThat(WorkflowGraphInvariants.isValid(ok)).isTrue();
    }

    // ── graph-driven runtime guard сохраняет self-approval ──────────────────────

    @Test
    void selfApprovalStillBlockedOnCustomCompliantGraph() {
        WorkflowGraph ok = WorkflowGraph.builder()
                .edge(Status.DRAFT, Status.IN_REVIEW,
                        Action.submit, Kind.SUBMIT, false, false, false)
                .edge(Status.IN_REVIEW, Status.STEWARD_APPROVED,
                        Action.steward_approve, Kind.STEWARD, false, true, false)
                .edge(Status.STEWARD_APPROVED, Status.OWNER_APPROVED,
                        Action.owner_approve, Kind.OWNER, false, false, true)
                .build();
        WorkflowGraphInvariants.validate(ok); // прошёл сеть

        UUID author = UUID.randomUUID();
        // Автор пытается сам же steward_approve на КАСТОМНОМ графе → 409.
        Request selfSteward = new Request(
                Status.IN_REVIEW, Status.STEWARD_APPROVED,
                author, author, Set.of(), Set.of(), Set.of("RDM_STEWARD"), null);
        assertThatThrownBy(() -> StateMachine.validate(selfSteward, ok))
                .isInstanceOf(WorkflowPort.SelfApprovalException.class);

        // Легитимный steward (≠author) с ролью — проходит, recordReviewer.
        Request realSteward = new Request(
                Status.IN_REVIEW, Status.STEWARD_APPROVED,
                UUID.randomUUID(), author, Set.of(), Set.of(), Set.of("RDM_STEWARD"), null);
        StateMachine.Decision d = StateMachine.validate(realSteward, ok);
        assertThat(d.action()).isEqualTo(Action.steward_approve);
        assertThat(d.recordReviewer()).isTrue();
    }

    @Test
    void defaultGraphValidateMatchesLegacyEntrypoint() {
        // validate(req) делегирует в validate(req, defaultFourEyes()) — parity.
        UUID a = UUID.randomUUID();
        Request submit = new Request(
                Status.DRAFT, Status.IN_REVIEW, a, a,
                Set.of(), Set.of(), Set.of("RDM_AUTHOR"), null);
        assertThat(StateMachine.validate(submit).action()).isEqualTo(Action.submit);
        assertThat(StateMachine.validate(submit, WorkflowGraph.defaultFourEyes()).action())
                .isEqualTo(Action.submit);
    }
}
