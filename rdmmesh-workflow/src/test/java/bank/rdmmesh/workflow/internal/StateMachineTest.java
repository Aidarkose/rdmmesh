package bank.rdmmesh.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import bank.rdmmesh.api.port.WorkflowPort.IllegalStateTransitionException;
import bank.rdmmesh.api.port.WorkflowPort.InsufficientRoleException;
import bank.rdmmesh.api.port.WorkflowPort.SelfApprovalException;
import bank.rdmmesh.workflow.internal.StateMachine.Action;
import bank.rdmmesh.workflow.internal.StateMachine.Decision;
import bank.rdmmesh.workflow.internal.StateMachine.Request;
import bank.rdmmesh.workflow.internal.StateMachine.Status;

/**
 * Unit-тесты pure-логики state machine'ы. Не требуют БД — все side-effects
 * лежат в {@code WorkflowService}, тут только чистая валидация переходов.
 */
class StateMachineTest {

    private static final UUID AUTHOR    = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID STEWARD_A = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OWNER_A   = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void resolveAction_maps_known_edges() {
        assertThat(StateMachine.resolveAction(Status.DRAFT, Status.IN_REVIEW))
                .isEqualTo(Action.submit);
        assertThat(StateMachine.resolveAction(Status.IN_REVIEW, Status.STEWARD_APPROVED))
                .isEqualTo(Action.steward_approve);
        assertThat(StateMachine.resolveAction(Status.IN_REVIEW, Status.DRAFT))
                .isEqualTo(Action.steward_reject);
        assertThat(StateMachine.resolveAction(Status.STEWARD_APPROVED, Status.OWNER_APPROVED))
                .isEqualTo(Action.owner_approve);
        assertThat(StateMachine.resolveAction(Status.STEWARD_APPROVED, Status.DRAFT))
                .isEqualTo(Action.owner_reject);
    }

    @Test
    void resolveAction_rejects_unknown_edges() {
        assertThatThrownBy(() -> StateMachine.resolveAction(Status.DRAFT, Status.OWNER_APPROVED))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> StateMachine.resolveAction(Status.DRAFT, Status.PUBLISHED))
                .isInstanceOf(IllegalStateTransitionException.class);
        assertThatThrownBy(() -> StateMachine.resolveAction(Status.OWNER_APPROVED, Status.DRAFT))
                .isInstanceOf(IllegalStateTransitionException.class);
    }

    @Test
    void resolveAction_maps_publish_and_deprecate() {
        // E6: publish/deprecate теперь маршрутизируются (вызываются PublishingService).
        assertThat(StateMachine.resolveAction(Status.OWNER_APPROVED, Status.PUBLISHED))
                .isEqualTo(Action.publish);
        assertThat(StateMachine.resolveAction(Status.PUBLISHED, Status.DEPRECATED))
                .isEqualTo(Action.deprecate);
    }

    @Test
    void publish_requires_RDM_SYSTEM_base_role() {
        // Без RDM_SYSTEM любой пользователь, даже OWNER, не может выполнить publish напрямую.
        assertThatThrownBy(() -> StateMachine.validate(req(Status.OWNER_APPROVED, Status.PUBLISHED,
                OWNER_A, AUTHOR, Set.of(STEWARD_A), Set.of("OWNER"), Set.of("RDM_OWNER"), null)))
                .isInstanceOf(InsufficientRoleException.class)
                .hasMessageContaining("RDM_SYSTEM");
    }

    @Test
    void publish_allowed_for_RDM_SYSTEM() {
        // PublishingService подменяет actor на SYSTEM и передаёт base-роль RDM_SYSTEM.
        Decision d = StateMachine.validate(req(Status.OWNER_APPROVED, Status.PUBLISHED,
                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                AUTHOR, Set.of(STEWARD_A), Set.of(), Set.of("RDM_SYSTEM"), null));
        assertThat(d.action()).isEqualTo(Action.publish);
        assertThat(d.recordReviewer()).isFalse();
        assertThat(d.setApprover()).isFalse();
    }

    @Test
    void deprecate_allowed_for_RDM_SYSTEM() {
        Decision d = StateMachine.validate(req(Status.PUBLISHED, Status.DEPRECATED,
                UUID.fromString("00000000-0000-0000-0000-000000000000"),
                AUTHOR, Set.of(), Set.of(), Set.of("RDM_SYSTEM"), null));
        assertThat(d.action()).isEqualTo(Action.deprecate);
    }

    @Test
    void submit_allowed_for_creator() {
        Decision d = StateMachine.validate(req(Status.DRAFT, Status.IN_REVIEW,
                AUTHOR, AUTHOR, Set.of(), Set.of(), Set.of(), null));
        assertThat(d.action()).isEqualTo(Action.submit);
        assertThat(d.recordReviewer()).isFalse();
        assertThat(d.setApprover()).isFalse();
    }

    @Test
    void submit_allowed_for_RDM_AUTHOR_base_role() {
        // Кто-то другой (не creator), но с base RDM_AUTHOR — допустим (substitute author).
        Decision d = StateMachine.validate(req(Status.DRAFT, Status.IN_REVIEW,
                STEWARD_A, AUTHOR, Set.of(), Set.of(), Set.of("RDM_AUTHOR"), null));
        assertThat(d.action()).isEqualTo(Action.submit);
    }

    @Test
    void submit_blocked_for_random_user() {
        assertThatThrownBy(() -> StateMachine.validate(req(Status.DRAFT, Status.IN_REVIEW,
                STEWARD_A, AUTHOR, Set.of(), Set.of(), Set.of("RDM_CONSUMER"), null)))
                .isInstanceOf(InsufficientRoleException.class);
    }

    @Test
    void steward_approve_blocks_self_approval() {
        // Author и Steward — один и тот же om_user_id.
        assertThatThrownBy(() -> StateMachine.validate(req(Status.IN_REVIEW, Status.STEWARD_APPROVED,
                AUTHOR, AUTHOR, Set.of(), Set.of("STEWARD"), Set.of("RDM_STEWARD"), null)))
                .isInstanceOf(SelfApprovalException.class);
    }

    @Test
    void steward_approve_passes_with_asset_role() {
        Decision d = StateMachine.validate(req(Status.IN_REVIEW, Status.STEWARD_APPROVED,
                STEWARD_A, AUTHOR, Set.of(), Set.of("STEWARD"), Set.of(), null));
        assertThat(d.recordReviewer()).isTrue();
        assertThat(d.setApprover()).isFalse();
    }

    @Test
    void steward_approve_passes_with_base_role_fallback() {
        // До E7 (OM webhook) asset-level STEWARD'ов нет в БД — fallback на base role.
        Decision d = StateMachine.validate(req(Status.IN_REVIEW, Status.STEWARD_APPROVED,
                STEWARD_A, AUTHOR, Set.of(), Set.of(), Set.of("RDM_STEWARD"), null));
        assertThat(d.action()).isEqualTo(Action.steward_approve);
    }

    @Test
    void steward_approve_blocks_without_role() {
        assertThatThrownBy(() -> StateMachine.validate(req(Status.IN_REVIEW, Status.STEWARD_APPROVED,
                STEWARD_A, AUTHOR, Set.of(), Set.of(), Set.of("RDM_CONSUMER"), null)))
                .isInstanceOf(InsufficientRoleException.class);
    }

    @Test
    void steward_reject_requires_comment() {
        assertThatThrownBy(() -> StateMachine.validate(req(Status.IN_REVIEW, Status.DRAFT,
                STEWARD_A, AUTHOR, Set.of(), Set.of("STEWARD"), Set.of(), null)))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("comment");

        assertThatThrownBy(() -> StateMachine.validate(req(Status.IN_REVIEW, Status.DRAFT,
                STEWARD_A, AUTHOR, Set.of(), Set.of("STEWARD"), Set.of(), "  ")))
                .isInstanceOf(IllegalStateTransitionException.class);

        Decision d = StateMachine.validate(req(Status.IN_REVIEW, Status.DRAFT,
                STEWARD_A, AUTHOR, Set.of(), Set.of("STEWARD"), Set.of(), "Не хватает stage 3"));
        assertThat(d.action()).isEqualTo(Action.steward_reject);
    }

    @Test
    void owner_approve_blocks_creator() {
        assertThatThrownBy(() -> StateMachine.validate(req(Status.STEWARD_APPROVED, Status.OWNER_APPROVED,
                AUTHOR, AUTHOR, Set.of(STEWARD_A), Set.of("OWNER"), Set.of(), null)))
                .isInstanceOf(SelfApprovalException.class)
                .hasMessageContaining("created_by");
    }

    @Test
    void owner_approve_blocks_previous_steward() {
        // Actor ранее одобрил как steward.
        assertThatThrownBy(() -> StateMachine.validate(req(Status.STEWARD_APPROVED, Status.OWNER_APPROVED,
                STEWARD_A, AUTHOR, Set.of(STEWARD_A), Set.of("OWNER"), Set.of(), null)))
                .isInstanceOf(SelfApprovalException.class)
                .hasMessageContaining("steward");
    }

    @Test
    void owner_approve_passes_for_independent_owner() {
        Decision d = StateMachine.validate(req(Status.STEWARD_APPROVED, Status.OWNER_APPROVED,
                OWNER_A, AUTHOR, Set.of(STEWARD_A), Set.of("OWNER"), Set.of(), null));
        assertThat(d.action()).isEqualTo(Action.owner_approve);
        assertThat(d.setApprover()).isTrue();
        assertThat(d.recordReviewer()).isFalse();
    }

    @Test
    void owner_approve_with_base_RDM_OWNER_fallback() {
        Decision d = StateMachine.validate(req(Status.STEWARD_APPROVED, Status.OWNER_APPROVED,
                OWNER_A, AUTHOR, Set.of(STEWARD_A), Set.of(), Set.of("RDM_OWNER"), null));
        assertThat(d.action()).isEqualTo(Action.owner_approve);
    }

    @Test
    void admin_can_substitute_owner_but_self_approval_still_blocks() {
        // Admin не создавал draft и не выступал steward'ом — может owner_approve как substitute.
        Decision d = StateMachine.validate(req(Status.STEWARD_APPROVED, Status.OWNER_APPROVED,
                OWNER_A, AUTHOR, Set.of(STEWARD_A), Set.of(), Set.of("RDM_ADMIN"), null));
        assertThat(d.action()).isEqualTo(Action.owner_approve);

        // Но если admin сам создал draft — путь через self-approval всё равно закрыт.
        assertThatThrownBy(() -> StateMachine.validate(req(Status.STEWARD_APPROVED, Status.OWNER_APPROVED,
                AUTHOR, AUTHOR, Set.of(STEWARD_A), Set.of(), Set.of("RDM_ADMIN"), null)))
                .isInstanceOf(SelfApprovalException.class);
    }

    @Test
    void owner_reject_requires_comment() {
        assertThatThrownBy(() -> StateMachine.validate(req(Status.STEWARD_APPROVED, Status.DRAFT,
                OWNER_A, AUTHOR, Set.of(STEWARD_A), Set.of("OWNER"), Set.of(), null)))
                .isInstanceOf(IllegalStateTransitionException.class)
                .hasMessageContaining("comment");

        Decision d = StateMachine.validate(req(Status.STEWARD_APPROVED, Status.DRAFT,
                OWNER_A, AUTHOR, Set.of(STEWARD_A), Set.of("OWNER"), Set.of(), "выявлены ошибки"));
        assertThat(d.action()).isEqualTo(Action.owner_reject);
    }

    @Test
    void allowed_transitions_map_matches_spec() {
        var map = StateMachine.allowedTransitions();
        assertThat(map.get(Status.DRAFT)).containsExactlyInAnyOrder(Status.IN_REVIEW);
        assertThat(map.get(Status.IN_REVIEW)).containsExactlyInAnyOrder(Status.STEWARD_APPROVED, Status.DRAFT);
        assertThat(map.get(Status.STEWARD_APPROVED)).containsExactlyInAnyOrder(Status.OWNER_APPROVED, Status.DRAFT);
        assertThat(map.get(Status.OWNER_APPROVED)).containsExactlyInAnyOrder(Status.PUBLISHED);
        assertThat(map.get(Status.PUBLISHED)).containsExactlyInAnyOrder(Status.DEPRECATED);
        // DEPRECATED / REJECTED — terminal.
        assertThat(map.get(Status.DEPRECATED)).isNull();
        assertThat(map.get(Status.REJECTED)).isNull();
    }

    @Test
    void next_required_role_after_each_status() {
        assertThat(StateMachine.nextRequiredRole(Status.IN_REVIEW)).isEqualTo("STEWARD");
        assertThat(StateMachine.nextRequiredRole(Status.STEWARD_APPROVED)).isEqualTo("OWNER");
        assertThat(StateMachine.nextRequiredRole(Status.OWNER_APPROVED)).isNull();
        assertThat(StateMachine.nextRequiredRole(Status.DRAFT)).isNull();
        assertThat(StateMachine.nextRequiredRole(Status.PUBLISHED)).isNull();
    }

    private static Request req(
            Status from, Status to,
            UUID actor, UUID createdBy,
            Set<UUID> reviewers,
            Set<String> assetRoles,
            Set<String> baseRoles,
            String comment) {
        return new Request(from, to, actor, createdBy, reviewers, assetRoles, baseRoles, comment);
    }
}
