package bank.rdmmesh.workflow.internal;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import bank.rdmmesh.workflow.internal.StateMachine.Action;
import bank.rdmmesh.workflow.internal.StateMachine.Status;

/**
 * Топология workflow как <b>данные</b> (V2 / BR-18, ADR-0010 — вариант B).
 *
 * <p>До B легальные рёбра были hardcoded enum-матрицей в {@link StateMachine}.
 * Теперь граф — значение: {@link #defaultFourEyes()} воспроизводит прежнюю
 * матрицу 1:1 (нулевое изменение поведения дефолта), а per-domain BPMN в
 * следующем слайсе будет задавать свой граф.
 *
 * <p><b>Гарантия no-bypass больше НЕ по построению</b> (это и есть цена B,
 * ADR-0010): любой граф ОБЯЗАН пройти {@link WorkflowGraphInvariants} перед
 * использованием — статическая сеть, доказывающая, что нельзя достичь
 * pre-publish-терминала {@code OWNER_APPROVED} не пройдя STEWARD-ребро, затем
 * OWNER-ребро (runtime-guard'ы {@link StateMachine} на этих {@link Kind}
 * обеспечивают, что это РАЗНЫЕ лица). SUBMIT/SYSTEM-рёбра в терминал вести
 * не могут.
 */
public final class WorkflowGraph {

    /**
     * Семантический класс ребра — определяет runtime-guard в
     * {@link StateMachine#validate(StateMachine.Request, WorkflowGraph)}:
     * <ul>
     *   <li>{@code SUBMIT} — author либо base RDM_AUTHOR/ADMIN; без
     *       self-approval;</li>
     *   <li>{@code STEWARD} — роль STEWARD; {@code actor ≠ created_by};</li>
     *   <li>{@code OWNER} — роль OWNER; {@code actor ≠ created_by} И
     *       {@code actor ∉ reviewers};</li>
     *   <li>{@code SYSTEM} — только RDM_SYSTEM (publish/deprecate —
     *       PublishingService).</li>
     * </ul>
     */
    public enum Kind { SUBMIT, STEWARD, OWNER, SYSTEM }

    public record Edge(Status from, Status to) {}

    public record EdgeSpec(
            Action action,
            Kind kind,
            boolean reject,
            boolean recordReviewer,
            boolean setApprover) {}

    private final Map<Edge, EdgeSpec> edges;

    private WorkflowGraph(Map<Edge, EdgeSpec> edges) {
        this.edges = Map.copyOf(edges);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Дефолтная 4-eyes-матрица — БАЙТ-в-байт прежнее поведение E5. */
    public static WorkflowGraph defaultFourEyes() {
        return builder()
                .edge(Status.DRAFT, Status.IN_REVIEW,
                        Action.submit, Kind.SUBMIT, false, false, false)
                .edge(Status.IN_REVIEW, Status.STEWARD_APPROVED,
                        Action.steward_approve, Kind.STEWARD, false, true, false)
                .edge(Status.IN_REVIEW, Status.DRAFT,
                        Action.steward_reject, Kind.STEWARD, true, false, false)
                .edge(Status.STEWARD_APPROVED, Status.OWNER_APPROVED,
                        Action.owner_approve, Kind.OWNER, false, false, true)
                .edge(Status.STEWARD_APPROVED, Status.DRAFT,
                        Action.owner_reject, Kind.OWNER, true, false, false)
                .edge(Status.OWNER_APPROVED, Status.PUBLISHED,
                        Action.publish, Kind.SYSTEM, false, false, false)
                .edge(Status.PUBLISHED, Status.DEPRECATED,
                        Action.deprecate, Kind.SYSTEM, false, false, false)
                .build();
    }

    public Optional<EdgeSpec> edge(Status from, Status to) {
        return Optional.ofNullable(edges.get(new Edge(from, to)));
    }

    public Map<Edge, EdgeSpec> edges() {
        return edges;
    }

    /**
     * Какая роль требуется на approval-task'е ПОСЛЕ достижения
     * {@code reached} — по исходящим non-reject рёбрам: STEWARD-ребро →
     * {@code "STEWARD"}, иначе OWNER → {@code "OWNER"}, иначе {@code null}.
     * Для {@link #defaultFourEyes()} == прежний
     * {@code StateMachine.nextRequiredRole} (IN_REVIEW→STEWARD,
     * STEWARD_APPROVED→OWNER, прочее→null).
     */
    public String nextRequiredRole(Status reached) {
        boolean steward = false;
        boolean owner = false;
        for (Map.Entry<Edge, EdgeSpec> e : edges.entrySet()) {
            if (e.getKey().from() != reached || e.getValue().reject()) {
                continue;
            }
            if (e.getValue().kind() == Kind.STEWARD) {
                steward = true;
            } else if (e.getValue().kind() == Kind.OWNER) {
                owner = true;
            }
        }
        if (steward) {
            return "STEWARD";
        }
        return owner ? "OWNER" : null;
    }

    /** Карта смежности (для инвариантов / документации). */
    public Map<Status, EnumSet<Status>> adjacency() {
        Map<Status, EnumSet<Status>> m = new LinkedHashMap<>();
        for (Edge e : edges.keySet()) {
            m.computeIfAbsent(e.from(), k -> EnumSet.noneOf(Status.class)).add(e.to());
        }
        return m;
    }

    public static final class Builder {
        private final Map<Edge, EdgeSpec> edges = new LinkedHashMap<>();

        public Builder edge(Status from, Status to, Action action, Kind kind,
                            boolean reject, boolean recordReviewer, boolean setApprover) {
            edges.put(new Edge(from, to),
                    new EdgeSpec(action, kind, reject, recordReviewer, setApprover));
            return this;
        }

        public WorkflowGraph build() {
            if (edges.isEmpty()) {
                throw new IllegalArgumentException("WorkflowGraph пуст");
            }
            return new WorkflowGraph(edges);
        }
    }
}
