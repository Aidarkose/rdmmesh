package bank.rdmmesh.workflow.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.flowable.bpmn.model.ExtensionElement;
import org.flowable.bpmn.model.Process;

import bank.rdmmesh.workflow.internal.StateMachine.Action;
import bank.rdmmesh.workflow.internal.StateMachine.Status;
import bank.rdmmesh.workflow.internal.WorkflowGraph.Kind;

/**
 * Кодек {@link WorkflowGraph} ⇄ JSON и извлечение графа из BPMN
 * (V2 / BR-18, ADR-0010 B2).
 *
 * <p>Per-domain BPMN несёт граф топологии как process-level
 * extension-элемент {@code <rdm:workflowGraph>} с JSON-массивом рёбер:
 * <pre>
 * [{"from":"DRAFT","to":"IN_REVIEW","action":"submit","kind":"SUBMIT"},
 *  {"from":"IN_REVIEW","to":"STEWARD_APPROVED","action":"steward_approve",
 *   "kind":"STEWARD","recordReviewer":true}, ...]
 * </pre>
 * Поля {@code reject/recordReviewer/setApprover} опциональны (default
 * false). Граф ОБЯЗАН пройти {@link WorkflowGraphInvariants} (deploy-time
 * gate в {@link bank.rdmmesh.workflow.internal.engine.BpmnTemplateValidator}).
 * Канонический JSON ({@link #toJson}) — детерминированно отсортирован
 * (воспроизводимость, хранится в {@code workflow_template.graph_json}).
 */
public final class WorkflowGraphCodec {

    /** Имя extension-элемента в BPMN (namespace значения не несёт). */
    public static final String EXTENSION = "workflowGraph";

    private static final ObjectMapper JSON = new ObjectMapper();

    private WorkflowGraphCodec() {}

    /** Граф из BPMN-процесса, если есть {@code <rdm:workflowGraph>}. */
    public static Optional<WorkflowGraph> fromBpmn(Process process) {
        Map<String, List<ExtensionElement>> ext = process.getExtensionElements();
        if (ext == null) {
            return Optional.empty();
        }
        List<ExtensionElement> els = ext.get(EXTENSION);
        if (els == null || els.isEmpty()) {
            return Optional.empty();
        }
        String json = els.get(0).getElementText();
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException(
                    "BPMN <rdm:" + EXTENSION + "> пуст");
        }
        return Optional.of(fromJson(json));
    }

    /** Парс JSON-массива рёбер в {@link WorkflowGraph}. */
    public static WorkflowGraph fromJson(String json) {
        List<Map<String, Object>> rows;
        try {
            rows = JSON.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "workflowGraph: невалидный JSON: " + e.getMessage(), e);
        }
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("workflowGraph: пустой массив рёбер");
        }
        WorkflowGraph.Builder b = WorkflowGraph.builder();
        for (Map<String, Object> r : rows) {
            Status from = enumVal(Status.class, r.get("from"), "from");
            Status to = enumVal(Status.class, r.get("to"), "to");
            Action action = enumVal(Action.class, r.get("action"), "action");
            Kind kind = enumVal(Kind.class, r.get("kind"), "kind");
            b.edge(from, to, action, kind,
                    bool(r.get("reject")),
                    bool(r.get("recordReviewer")),
                    bool(r.get("setApprover")));
        }
        return b.build();
    }

    /** Канонический (детерминированно отсортированный) JSON графа. */
    public static String toJson(WorkflowGraph g) {
        List<Map<String, Object>> rows = new ArrayList<>();
        g.edges().entrySet().stream()
                .sorted(Comparator
                        .comparing((Map.Entry<WorkflowGraph.Edge, WorkflowGraph.EdgeSpec> e)
                                -> e.getKey().from().name())
                        .thenComparing(e -> e.getKey().to().name()))
                .forEach(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("from", e.getKey().from().name());
                    m.put("to", e.getKey().to().name());
                    m.put("action", e.getValue().action().name());
                    m.put("kind", e.getValue().kind().name());
                    m.put("reject", e.getValue().reject());
                    m.put("recordReviewer", e.getValue().recordReviewer());
                    m.put("setApprover", e.getValue().setApprover());
                    rows.add(m);
                });
        try {
            return JSON.writeValueAsString(rows);
        } catch (Exception e) {
            throw new IllegalStateException("workflowGraph: сериализация: " + e.getMessage(), e);
        }
    }

    private static <E extends Enum<E>> E enumVal(Class<E> type, Object raw, String field) {
        if (raw == null) {
            throw new IllegalArgumentException("workflowGraph: поле '" + field + "' обязательно");
        }
        try {
            return Enum.valueOf(type, raw.toString().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("workflowGraph: '" + field + "'='"
                    + raw + "' не из " + type.getSimpleName());
        }
    }

    private static boolean bool(Object o) {
        return o != null && Boolean.parseBoolean(o.toString());
    }
}
