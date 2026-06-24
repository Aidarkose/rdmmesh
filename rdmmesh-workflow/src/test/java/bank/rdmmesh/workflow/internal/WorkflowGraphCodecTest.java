package bank.rdmmesh.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import bank.rdmmesh.workflow.internal.StateMachine.Status;

/**
 * ADR-0010 B2 — кодек {@link WorkflowGraph} ⇄ JSON. Чистый unit
 * (Jackson на classpath, без БД/Flowable) — гоняется локально.
 */
final class WorkflowGraphCodecTest {

    @Test
    void defaultGraphRoundtripsAndStaysCompliant() {
        WorkflowGraph def = WorkflowGraph.defaultFourEyes();
        String json = WorkflowGraphCodec.toJson(def);
        WorkflowGraph back = WorkflowGraphCodec.fromJson(json);

        assertThat(back.edges()).hasSize(def.edges().size());
        WorkflowGraphInvariants.validate(back); // не бросает
        // nextRequiredRole-паритет на ключевых статусах.
        assertThat(back.nextRequiredRole(Status.IN_REVIEW)).isEqualTo("STEWARD");
        assertThat(back.nextRequiredRole(Status.STEWARD_APPROVED)).isEqualTo("OWNER");
        assertThat(back.nextRequiredRole(Status.DRAFT)).isNull();
        // канонический JSON детерминирован.
        assertThat(WorkflowGraphCodec.toJson(back)).isEqualTo(json);
    }

    @Test
    void invalidJsonRejected() {
        assertThatThrownBy(() -> WorkflowGraphCodec.fromJson("{not-json"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void emptyArrayRejected() {
        assertThatThrownBy(() -> WorkflowGraphCodec.fromJson("[]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownEnumRejected() {
        String bad = "[{\"from\":\"DRAFT\",\"to\":\"IN_REVIEW\","
                + "\"action\":\"submit\",\"kind\":\"WIZARD\"}]";
        assertThatThrownBy(() -> WorkflowGraphCodec.fromJson(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind");
    }

    @Test
    void missingRequiredFieldRejected() {
        String bad = "[{\"from\":\"DRAFT\",\"to\":\"IN_REVIEW\",\"kind\":\"SUBMIT\"}]";
        assertThatThrownBy(() -> WorkflowGraphCodec.fromJson(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
    }
}
