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
 * OWNER-approve-ребро. Runtime-guard {@link StateMachine#validate(
 * StateMachine.Request, WorkflowGraph)} на {@link Kind#OWNER}
 * ({@code actor ≠ created_by} И {@code actor ∉ reviewers}) гарантирует, что
 * владелец — <b>иное лицо</b>, нежели автор (и любой steward-reviewer, если
 * он был) — т.е. минимум <b>2-eyes</b> (author/steward ≠ owner) сохраняется
 * для ЛЮБОГО прошедшего графа. Это согласованная модель Phase 3: маршрут
 * строго {@code STEWARD(author+submit) → OWNER}, отдельная STEWARD-approve-
 * ступень НЕ обязательна. Если граф её всё же содержит (классический 4-eyes),
 * порядок review→approve сохраняется и независимых лиц становится три.
 *
 * <h3>Проверяемые инварианты</h3>
 * <ol>
 *   <li>{@code OWNER_APPROVED} достижим из {@code DRAFT} (есть путь);</li>
 *   <li>любой простой путь {@code DRAFT → OWNER_APPROVED} содержит
 *       OWNER-approve-ребро (kind=OWNER, !reject); если на пути есть
 *       STEWARD-approve-ребро — OWNER идёт строго после него;</li>
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
            // (2, Phase 3) OWNER-approve-ребро ОБЯЗАТЕЛЬНО на пути в терминал —
            // независимость лиц (creator ≠ owner, т.е. 2-eyes) гарантирует
            // OWNER-guard StateMachine (actor ≠ created_by). Маршрут
            // STEWARD(author+submit) → OWNER легитимен и не содержит отдельного
            // STEWARD-approve-ребра. Правило (3) уже гарантирует, что В терминал
            // ведёт ТОЛЬКО OWNER-ребро, так что обойти OWNER-approve нельзя.
            if (owner < 0) {
                throw new IllegalArgumentException(
                        "Compliance: путь до " + APPROVAL_TERMINAL
                                + " без OWNER-approve-ребра — владелец не подтвердил");
            }
            // Если граф ВСЁ ЖЕ содержит STEWARD-approve-ступень (классический
            // 4-eyes), OWNER обязан идти ПОСЛЕ неё — порядок review→approve
            // сохраняется (3-eyes для таких графов не деградирует).
            if (steward >= 0 && owner <= steward) {
                throw new IllegalArgumentException(
                        "Compliance: путь до " + APPROVAL_TERMINAL
                                + " с OWNER-approve ДО STEWARD-approve — порядок 4-eyes нарушен");
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
