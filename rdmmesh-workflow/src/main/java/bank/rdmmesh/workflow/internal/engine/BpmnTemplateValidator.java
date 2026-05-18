package bank.rdmmesh.workflow.internal.engine;

import java.io.ByteArrayInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

import bank.rdmmesh.workflow.internal.WorkflowGraph;
import bank.rdmmesh.workflow.internal.WorkflowGraphCodec;
import bank.rdmmesh.workflow.internal.WorkflowGraphInvariants;

import org.flowable.bpmn.converter.BpmnXMLConverter;
import org.flowable.bpmn.model.BpmnModel;
import org.flowable.bpmn.model.ImplementationType;
import org.flowable.bpmn.model.Process;
import org.flowable.bpmn.model.ReceiveTask;
import org.flowable.bpmn.model.ServiceTask;
import org.flowable.common.engine.impl.util.io.InputStreamSource;

/**
 * Контракт per-domain BPMN-шаблона (V2 / BR-18 round 2, ADR-0009 модель A).
 *
 * <p>Кастомная топология домена может переименовывать/добавлять задачи,
 * listeners, шлюзы — но ДОЛЖНА сохранить два якоря, на которых держится
 * движок (иначе версии этого домена просто не будут продвигаться):
 * <ul>
 *   <li>receive-task с id {@code rt_await} — точка, куда
 *       {@link FlowableWorkflowEngine} делает {@code trigger};</li>
 *   <li>service-task c {@code delegateExpression=${rdmTransitionDelegate}}
 *       — единственная связка с {@code WorkflowService} (там остаются
 *       авторитетные guard'ы; кастомный BPMN <b>не может</b> обойти
 *       4-eyes — модель A).</li>
 * </ul>
 *
 * <p>Глубокая «compliance-валидация» топологии (что есть именно steward+
 * owner-ступени и нет рёбер в обход) НЕ нужна в модели A: легальность
 * каждого перехода всё равно проверяет enum-StateMachine в
 * {@code WorkflowService}. Здесь — лишь структурный контракт движка.
 */
public final class BpmnTemplateValidator {

    /** Обязательная связка с WorkflowService (см. {@code rdm-4eyes.bpmn20.xml}). */
    public static final String REQUIRED_DELEGATE = "${rdmTransitionDelegate}";

    private BpmnTemplateValidator() {}

    /**
     * Результат валидации: ключ (process id) + опц. per-domain граф
     * (ADR-0010 B2). {@code graph} пуст → домен использует дефолтный
     * 4-eyes. {@code graphJson} — канонический JSON для хранения в
     * {@code workflow_template.graph_json}.
     */
    public record Contract(
            String processKey,
            Optional<WorkflowGraph> graph,
            Optional<String> graphJson) {}

    /**
     * Парсит BPMN и проверяет якоря контракта.
     *
     * @throws IllegalArgumentException если BPMN не парсится либо не
     *         удовлетворяет контракту (resource → 400).
     */
    public static Contract validate(byte[] bpmnXml) {
        if (bpmnXml == null || bpmnXml.length == 0) {
            throw new IllegalArgumentException("BPMN пуст");
        }
        BpmnModel model;
        try {
            model = new BpmnXMLConverter().convertToBpmnModel(
                    new InputStreamSource(new ByteArrayInputStream(bpmnXml)),
                    /* validateSchema */ false,
                    /* enableSafeBpmnXml */ true);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "BPMN не распарсился: " + e.getMessage(), e);
        }
        Process process = model.getMainProcess();
        if (process == null || process.getId() == null || process.getId().isBlank()) {
            throw new IllegalArgumentException(
                    "BPMN: нет executable-процесса с id");
        }

        boolean hasAwait = process
                .findFlowElementsOfType(ReceiveTask.class).stream()
                .anyMatch(rt -> FlowableEngineManager.AWAIT_ACTIVITY.equals(rt.getId()));
        if (!hasAwait) {
            throw new IllegalArgumentException(
                    "BPMN не соответствует контракту шаблона: нет receive-task "
                            + "с id '" + FlowableEngineManager.AWAIT_ACTIVITY + "'");
        }

        List<ServiceTask> services = process.findFlowElementsOfType(ServiceTask.class);
        boolean hasDelegate = services.stream().anyMatch(st ->
                ImplementationType.IMPLEMENTATION_TYPE_DELEGATEEXPRESSION
                        .equals(st.getImplementationType())
                        && REQUIRED_DELEGATE.equals(st.getImplementation()));
        if (!hasDelegate) {
            throw new IllegalArgumentException(
                    "BPMN не соответствует контракту шаблона: нет service-task "
                            + "с delegateExpression " + REQUIRED_DELEGATE);
        }

        // ADR-0010 B2: если BPMN несёт <rdm:workflowGraph> — это deploy-time
        // compliance-gate. Граф ОБЯЗАН пройти WorkflowGraphInvariants
        // (нельзя обойти 4-eyes); невалидный → 400, в Flowable/реестр НЕ
        // попадает. Нет графа → домен остаётся на дефолтном 4-eyes.
        Optional<WorkflowGraph> graph = WorkflowGraphCodec.fromBpmn(process);
        graph.ifPresent(WorkflowGraphInvariants::validate);
        Optional<String> graphJson = graph.map(WorkflowGraphCodec::toJson);
        return new Contract(process.getId(), graph, graphJson);
    }

    /** SHA-256 (hex) BPMN-XML — для реестра/воспроизводимости (V032). */
    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(bytes);
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}
