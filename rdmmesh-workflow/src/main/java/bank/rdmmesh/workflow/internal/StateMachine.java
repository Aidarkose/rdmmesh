package bank.rdmmesh.workflow.internal;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import bank.rdmmesh.api.port.WorkflowPort.IllegalStateTransitionException;
import bank.rdmmesh.api.port.WorkflowPort.InsufficientRoleException;
import bank.rdmmesh.api.port.WorkflowPort.SelfApprovalException;

/**
 * Pure-функциональное ядро 4-eyes workflow. Знает только про состояния, действия и
 * правила перехода — никаких БД, никаких побочных эффектов. Все side-эффекты
 * (CAS статуса, INSERT в reviewer-list, выдача approval-task) делает {@code WorkflowService}.
 *
 * <h3>Матрица переходов (E5 scope, см. SPEC §2.2 этап 3):</h3>
 * <pre>
 *   DRAFT             ──submit─────────▶ IN_REVIEW
 *   IN_REVIEW         ──steward_approve─▶ STEWARD_APPROVED
 *   IN_REVIEW         ──steward_reject──▶ DRAFT          (comment обязателен)
 *   STEWARD_APPROVED  ──owner_approve───▶ OWNER_APPROVED
 *   STEWARD_APPROVED  ──owner_reject────▶ DRAFT          (comment обязателен)
 * </pre>
 *
 * <p>Действия {@code publish} (OWNER_APPROVED → PUBLISHED) и {@code deprecate}
 * (PUBLISHED → DEPRECATED) — system-actions: вызываются publishing-модулем (E6) как
 * постпроцесс после OWNER_APPROVED. Self-approval проверки тут не применяются —
 * approver уже зафиксирован на предыдущем шаге; role-gate тоже снят, потому что
 * вызывает их сам сервер (не пользователь). Снаружи через REST публиковать нельзя,
 * это делает только PublishingService после {@code WorkflowTransitionDomainEvent}
 * с {@code to=OWNER_APPROVED}.
 *
 * <h3>Self-approval prevention (SPEC §2.1, §3.8):</h3>
 * <ul>
 *   <li>steward_approve / steward_reject: actor ≠ created_by</li>
 *   <li>owner_approve   / owner_reject  : actor ≠ created_by И actor ∉ reviewers (steward'ы)</li>
 * </ul>
 *
 * <h3>Role gate (SPEC §2.1):</h3>
 * Базовые функциональные роли (RDM_AUTHOR/STEWARD/OWNER/ADMIN) приходят из Keycloak/AD.
 * Asset-level роли (STEWARD/OWNER на конкретном CodeSet) — из rdm_asset_ownership через
 * {@code OwnershipPort.rolesOf()}. Действие разрешено, если **либо** asset-level роль
 * совпадает, **либо** есть base-роль того же класса. До эпика E7 (OM ownership webhook)
 * asset-level STEWARD'ов в БД нет — base-роли работают как fallback.
 *
 * <h3>RDM_ADMIN не может обходить state machine (SPEC §3.2 #7):</h3>
 * Admin может действовать как steward или owner (выручка-эскалация), но self-approval
 * prevention применяется к нему точно так же. Прыгнуть из DRAFT сразу в OWNER_APPROVED
 * нельзя ни одной комбинации ролей.
 */
public final class StateMachine {

    public enum Status { DRAFT, IN_REVIEW, STEWARD_APPROVED, OWNER_APPROVED, PUBLISHED, DEPRECATED, REJECTED }

    public enum Action { submit, steward_approve, steward_reject, owner_approve, owner_reject, publish, deprecate }

    private static final Map<Edge, Action> EDGE_TO_ACTION;

    static {
        EDGE_TO_ACTION = new java.util.HashMap<>();
        EDGE_TO_ACTION.put(new Edge(Status.DRAFT, Status.IN_REVIEW), Action.submit);
        EDGE_TO_ACTION.put(new Edge(Status.IN_REVIEW, Status.STEWARD_APPROVED), Action.steward_approve);
        EDGE_TO_ACTION.put(new Edge(Status.IN_REVIEW, Status.DRAFT), Action.steward_reject);
        EDGE_TO_ACTION.put(new Edge(Status.STEWARD_APPROVED, Status.OWNER_APPROVED), Action.owner_approve);
        EDGE_TO_ACTION.put(new Edge(Status.STEWARD_APPROVED, Status.DRAFT), Action.owner_reject);
        EDGE_TO_ACTION.put(new Edge(Status.OWNER_APPROVED, Status.PUBLISHED), Action.publish);
        EDGE_TO_ACTION.put(new Edge(Status.PUBLISHED, Status.DEPRECATED), Action.deprecate);
    }

    private static final Set<Action> IMPLEMENTED = EnumSet.of(
            Action.submit,
            Action.steward_approve,
            Action.steward_reject,
            Action.owner_approve,
            Action.owner_reject,
            Action.publish,
            Action.deprecate);

    private StateMachine() {}

    /** Маппинг (from,to) → action. {@link IllegalStateTransitionException}, если переход не из таблицы. */
    public static Action resolveAction(Status from, Status to) {
        Action a = EDGE_TO_ACTION.get(new Edge(from, to));
        if (a == null) {
            throw new IllegalStateTransitionException(
                    "Переход " + from + " → " + to + " не разрешён state machine'ой");
        }
        if (!IMPLEMENTED.contains(a)) {
            throw new IllegalStateTransitionException(
                    "Действие " + a + " (" + from + " → " + to + ") пока не реализовано");
        }
        return a;
    }

    /**
     * Полная валидация перехода по <b>дефолтному</b> 4-eyes-графу
     * (back-compat: поведение E5 1:1). Делегирует в
     * {@link #validate(Request, WorkflowGraph)} с
     * {@link WorkflowGraph#defaultFourEyes()}.
     */
    public static Decision validate(Request req) {
        return validate(req, WorkflowGraph.defaultFourEyes());
    }

    /**
     * Валидация перехода по ПРОИЗВОЛЬНОМУ графу (V2 / BR-18, ADR-0010 B):
     * легальность ребра, role-gate и self-approval prevention по
     * {@link WorkflowGraph.Kind}, обязательность comment'а на reject.
     *
     * <p>Граф ДОЛЖЕН быть предварительно пропущен через
     * {@link WorkflowGraphInvariants} — иначе кастомный граф может не
     * содержать STEWARD/OWNER-ступеней (ADR-0010: no-bypass для
     * произвольных графов держится на инвариант-сети, не на коде здесь).
     */
    public static Decision validate(Request req, WorkflowGraph graph) {
        WorkflowGraph.EdgeSpec spec = graph.edge(req.from(), req.to())
                .orElseThrow(() -> new IllegalStateTransitionException(
                        "Переход " + req.from() + " → " + req.to()
                                + " не разрешён state machine'ой"));

        if (spec.reject()) {
            if (req.comment() == null || req.comment().isBlank()) {
                throw new IllegalStateTransitionException(
                        "Reject требует обязательный comment (" + spec.action() + ")");
            }
        }

        switch (spec.kind()) {
            case SUBMIT -> {
                // Submit делает Author. Проверка: либо актор — это создатель draft'а,
                // либо у актора есть base RDM_AUTHOR/RDM_ADMIN.
                if (!req.actor().equals(req.createdBy())
                        && !hasAny(req.baseRoles(), "RDM_AUTHOR", "RDM_ADMIN")) {
                    throw new InsufficientRoleException(
                            "submit разрешён только автору draft'а или RDM_AUTHOR/RDM_ADMIN");
                }
            }
            case STEWARD -> {
                if (req.actor().equals(req.createdBy())) {
                    throw new SelfApprovalException(
                            "Self-approval запрещён: actor совпадает с created_by");
                }
                requireStewardRole(req);
            }
            case OWNER -> {
                if (req.actor().equals(req.createdBy())) {
                    throw new SelfApprovalException(
                            "Self-approval запрещён: actor совпадает с created_by");
                }
                if (req.reviewers().contains(req.actor())) {
                    throw new SelfApprovalException(
                            "Self-approval запрещён: actor уже выступал steward'ом этой версии");
                }
                requireOwnerRole(req);
            }
            case SYSTEM -> {
                // System actions, see class-level Javadoc. Никаких role/self-approval
                // проверок — вызывает их PublishingService, не пользователь.
                if (!hasAny(req.baseRoles(), "RDM_SYSTEM")) {
                    throw new InsufficientRoleException(
                            "Действие " + spec.action()
                                    + " зарезервировано за PublishingService (RDM_SYSTEM)");
                }
            }
            default -> throw new IllegalStateException("Unreachable kind: " + spec.kind());
        }

        return new Decision(spec.action(), spec.recordReviewer(), spec.setApprover());
    }

    private static void requireStewardRole(Request req) {
        boolean assetSteward = req.assetRoles().contains("STEWARD");
        boolean baseSteward  = hasAny(req.baseRoles(), "RDM_STEWARD", "RDM_ADMIN");
        if (!assetSteward && !baseSteward) {
            throw new InsufficientRoleException(
                    "Действие требует роль STEWARD на CodeSet (или базовую RDM_STEWARD/RDM_ADMIN)");
        }
    }

    private static void requireOwnerRole(Request req) {
        boolean assetOwner = req.assetRoles().contains("OWNER");
        boolean baseOwner  = hasAny(req.baseRoles(), "RDM_OWNER", "RDM_ADMIN");
        if (!assetOwner && !baseOwner) {
            throw new InsufficientRoleException(
                    "Действие требует роль OWNER на CodeSet (или базовую RDM_OWNER/RDM_ADMIN)");
        }
    }

    private static boolean hasAny(Set<String> roles, String... candidates) {
        if (roles == null || roles.isEmpty()) return false;
        for (String c : candidates) if (roles.contains(c)) return true;
        return false;
    }

    /** После какого статуса какая роль требуется на следующем шаге (для approval_task). */
    public static String nextRequiredRole(Status reachedStatus) {
        return switch (reachedStatus) {
            case IN_REVIEW -> "STEWARD";
            case STEWARD_APPROVED -> "OWNER";
            default -> null;
        };
    }

    /** Полная карта легальных переходов — для unit-тестов и документации. */
    public static Map<Status, EnumSet<Status>> allowedTransitions() {
        Map<Status, EnumSet<Status>> map = new EnumMap<>(Status.class);
        for (Edge edge : EDGE_TO_ACTION.keySet()) {
            map.computeIfAbsent(edge.from(), k -> EnumSet.noneOf(Status.class)).add(edge.to());
        }
        return map;
    }

    public record Request(
            Status from,
            Status to,
            UUID actor,
            UUID createdBy,
            Set<UUID> reviewers,
            Set<String> assetRoles,
            Set<String> baseRoles,
            String comment) {}

    public record Decision(Action action, boolean recordReviewer, boolean setApprover) {}

    private record Edge(Status from, Status to) {}
}
