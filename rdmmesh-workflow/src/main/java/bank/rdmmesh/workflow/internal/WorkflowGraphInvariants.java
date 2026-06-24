package bank.rdmmesh.workflow.internal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;

import bank.rdmmesh.workflow.internal.StateMachine.Status;
import bank.rdmmesh.workflow.internal.WorkflowGraph.Edge;
import bank.rdmmesh.workflow.internal.WorkflowGraph.EdgeSpec;
import bank.rdmmesh.workflow.internal.WorkflowGraph.Kind;

/**
 * Статическая compliance-сеть для произвольного {@link WorkflowGraph}
 * (V2 / BR-18, ADR-0010 вариант B). Это <b>замена</b> утраченной с B
 * гарантии «no-bypass по построению»: любой граф (особенно per-domain
 * кастомный) ОБЯЗАН пройти эту проверку перед использованием/деплоем.
 *
 * <h3>Теорема, которую доказывает валидатор</h3>
 * Достичь pre-publish-терминала {@code OWNER_APPROVED} нельзя, не пройдя
 * сперва STEWARD-approve-ребро, затем OWNER-approve-ребро. Runtime-guard'ы
 * {@link StateMachine#validate(StateMachine.Request, WorkflowGraph)} на
 * {@link Kind#STEWARD} ({@code actor ≠ created_by}) и {@link Kind#OWNER}
 * ({@code actor ≠ created_by} И {@code actor ∉ reviewers}) тогда
 * гарантируют, что это <b>три разных лица</b> (author, steward, owner) —
 * т.е. 4-eyes/self-approval сохраняются для ЛЮБОГО прошедшего графа.
 *
 * <h3>Проверяемые инварианты</h3>
 * <ol>
 *   <li>{@code OWNER_APPROVED} достижим из {@code DRAFT} (есть путь);</li>
 *   <li>любой простой путь {@code DRAFT → OWNER_APPROVED} содержит
 *       STEWARD-approve-ребро (kind=STEWARD, !reject), затем — позже —
 *       OWNER-approve-ребро (kind=OWNER, !reject);</li>
 *   <li>каждое ребро c {@code to == OWNER_APPROVED} — kind OWNER, !reject
 *       <b>и {@code setApprover=true}</b> (approver фиксируется, иначе
 *       нарушается целостность подписи/аудита E6);</li>
 *   <li>reject-ребро — только kind STEWARD/OWNER (reviewer-действие, не
 *       SUBMIT/SYSTEM);</li>
 *   <li><b>(B3, finding F-B1)</b> каждое non-reject STEWARD-ребро ОБЯЗАНО
 *       {@code recordReviewer=true}. Иначе steward не попадает в
 *       {@code reviewers}, и runtime-guard OWNER ({@code actor ∉
 *       reviewers}) НЕ ловит «steward==owner» — 4-eyes деградирует до
 *       2-eyes. Это и есть условие, на котором держится теорема;</li>
 *   <li><b>(B3, finding F-B4)</b> SYSTEM-ребро допустимо только формой
 *       {@code OWNER_APPROVED→PUBLISHED} либо {@code PUBLISHED→DEPRECATED}
 *       — нельзя протащить SYSTEM-shortcut (publish требует RDM_SYSTEM,
 *       но крафт-граф не должен и описывать иной системный маршрут).</li>
 * </ol>
 */
public final class WorkflowGraphInvariants {

    private static final Status INITIAL = Status.DRAFT;
    private static final Status APPROVAL_TERMINAL = Status.OWNER_APPROVED;

    private WorkflowGraphInvariants() {}

    /** @throws IllegalArgumentException с причиной, если граф не compliant. */
    public static void validate(WorkflowGraph g) {
        // (4) reject-рёбра — только reviewer-классов.
        for (var en : g.edges().entrySet()) {
            EdgeSpec s = en.getValue();
            if (s.reject() && s.kind() != Kind.STEWARD && s.kind() != Kind.OWNER) {
                throw new IllegalArgumentException(
                        "Compliance: reject-ребро " + en.getKey() + " должно быть "
                                + "kind STEWARD/OWNER, а не " + s.kind());
            }
        }
        for (var en : g.edges().entrySet()) {
            Edge e = en.getKey();
            EdgeSpec s = en.getValue();
            // (3) В терминал — только OWNER-approve c setApprover.
            if (e.to() == APPROVAL_TERMINAL
                    && (s.kind() != Kind.OWNER || s.reject() || !s.setApprover())) {
                throw new IllegalArgumentException(
                        "Compliance: ребро в " + APPROVAL_TERMINAL + " (" + e
                                + ") должно быть OWNER-approve (kind=OWNER, !reject, "
                                + "setApprover=true), а не " + s.kind()
                                + (s.reject() ? "/reject" : "")
                                + (s.setApprover() ? "" : "/no-setApprover"));
            }
            // (5, F-B1) non-reject STEWARD — обязан recordReviewer:
            // иначе steward не в reviewers и OWNER-guard не ловит
            // steward==owner (теорема no-bypass рушится).
            if (s.kind() == Kind.STEWARD && !s.reject() && !s.recordReviewer()) {
                throw new IllegalArgumentException(
                        "Compliance: STEWARD-approve-ребро " + e + " ОБЯЗАНО "
                                + "recordReviewer=true (иначе steward==owner обходит "
                                + "4-eyes — finding F-B1)");
            }
            // (6, F-B4) SYSTEM — только фиксированный publish/deprecate-маршрут.
            if (s.kind() == Kind.SYSTEM
                    && !(e.from() == Status.OWNER_APPROVED && e.to() == Status.PUBLISHED)
                    && !(e.from() == Status.PUBLISHED && e.to() == Status.DEPRECATED)) {
                throw new IllegalArgumentException(
                        "Compliance: SYSTEM-ребро " + e + " вне разрешённой формы "
                                + "(OWNER_APPROVED→PUBLISHED | PUBLISHED→DEPRECATED) "
                                + "— finding F-B4");
            }
        }
        // (1)+(2) Перебор всех простых путей DRAFT→OWNER_APPROVED.
        List<List<EdgeSpec>> paths = new ArrayList<>();
        dfs(g, INITIAL, EnumSet.of(INITIAL), new ArrayDeque<>(), paths);
        if (paths.isEmpty()) {
            throw new IllegalArgumentException(
                    "Compliance: " + APPROVAL_TERMINAL + " недостижим из " + INITIAL);
        }
        for (List<EdgeSpec> path : paths) {
            int steward = firstIndex(path, Kind.STEWARD);
            int owner = lastIndex(path, Kind.OWNER);
            if (steward < 0) {
                throw new IllegalArgumentException(
                        "Compliance: путь до " + APPROVAL_TERMINAL
                                + " без STEWARD-approve-ребра — 4-eyes обойдён");
            }
            if (owner < 0 || owner <= steward) {
                throw new IllegalArgumentException(
                        "Compliance: путь до " + APPROVAL_TERMINAL
                                + " без OWNER-approve ПОСЛЕ STEWARD — 4-eyes обойдён");
            }
        }
    }

    /** true, если граф compliant (без выброса). */
    public static boolean isValid(WorkflowGraph g) {
        try {
            validate(g);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Простые пути (без повтора статуса) INITIAL→APPROVAL_TERMINAL. */
    private static void dfs(WorkflowGraph g, Status at, EnumSet<Status> seen,
                            Deque<EdgeSpec> acc, List<List<EdgeSpec>> out) {
        if (at == APPROVAL_TERMINAL) {
            out.add(new ArrayList<>(acc));
            return;
        }
        for (var en : g.edges().entrySet()) {
            Edge e = en.getKey();
            if (e.from() != at || seen.contains(e.to())) {
                continue;
            }
            seen.add(e.to());
            acc.addLast(en.getValue());
            dfs(g, e.to(), seen, acc, out);
            acc.removeLast();
            seen.remove(e.to());
        }
    }

    private static int firstIndex(List<EdgeSpec> path, Kind kind) {
        for (int i = 0; i < path.size(); i++) {
            EdgeSpec s = path.get(i);
            if (s.kind() == kind && !s.reject()) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndex(List<EdgeSpec> path, Kind kind) {
        for (int i = path.size() - 1; i >= 0; i--) {
            EdgeSpec s = path.get(i);
            if (s.kind() == kind && !s.reject()) {
                return i;
            }
        }
        return -1;
    }
}
