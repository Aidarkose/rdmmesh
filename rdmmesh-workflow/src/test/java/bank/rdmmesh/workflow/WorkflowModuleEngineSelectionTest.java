package bank.rdmmesh.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.Test;

/**
 * Выбор движка в {@link WorkflowModule} (V2 / BR-18, ADR-009) — чистый
 * unit, без БД ({@code Jdbi.create} не подключается; порты не дёргаются).
 */
final class WorkflowModuleEngineSelectionTest {

    private static Jdbi noConnectJdbi() {
        return Jdbi.create("jdbc:postgresql://localhost:1/none", "u", "p");
    }

    @Test
    void enumIsDefaultAndHasNoEngineManager() {
        WorkflowModule.Resources r = WorkflowModule.build(
                noConnectJdbi(), null, null, null, null);
        assertThat(r.engineManager()).isEmpty();
        assertThat(r.transitions()).isNotNull();
        assertThat(r.service()).isNotNull();
    }

    @Test
    void flowableWithoutDbConfigIsRejected() {
        assertThatThrownBy(() -> WorkflowModule.build(
                        noConnectJdbi(), null, null, null, null,
                        null, WorkflowModule.EngineKind.FLOWABLE, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FlowableDbConfig");
    }
}
