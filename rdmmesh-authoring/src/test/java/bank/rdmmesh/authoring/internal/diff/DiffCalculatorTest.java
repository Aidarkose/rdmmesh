package bank.rdmmesh.authoring.internal.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import bank.rdmmesh.authoring.internal.dao.CodeItemDiffDao.DiffRow;

class DiffCalculatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final DiffCalculator differ = new DiffCalculator(JSON);

    @Test
    void added_and_removed_keys() {
        List<DiffRow> rows = List.of(
                row("ADDED",   "[\"NEW\"]", null,                       doc("\"new\"")),
                row("REMOVED", "[\"OLD\"]", doc("\"old\""),             null));
        var r = differ.compute("1.0.0", "1.1.0", rows);
        assertThat(r.summary().added()).isEqualTo(1);
        assertThat(r.summary().removed()).isEqualTo(1);
        assertThat(r.entries()).extracting(DiffCalculator.Entry::op)
                .containsExactlyInAnyOrder("ADDED", "REMOVED");
    }

    @Test
    void changed_attribute_is_flagged_as_attributes_dot_field() {
        DiffRow r = row("CHANGED", "[\"PD\"]",
                "{\"label_ru\":\"x\",\"attributes\":{\"pd\":0.1}}",
                "{\"label_ru\":\"x\",\"attributes\":{\"pd\":0.2}}");
        var res = differ.compute("1.0.0", "1.1.0", List.of(r));
        assertThat(res.summary().changed()).isEqualTo(1);
        assertThat(res.entries().get(0).changedFields()).containsExactly("attributes.pd");
    }

    @Test
    void changed_only_parent_key_becomes_MOVED() {
        DiffRow r = row("CHANGED", "[\"X\"]",
                "{\"label_ru\":\"l\",\"attributes\":{\"a\":1},\"parent_key\":[\"P1\"]}",
                "{\"label_ru\":\"l\",\"attributes\":{\"a\":1},\"parent_key\":[\"P2\"]}");
        var res = differ.compute("1.0.0", "1.1.0", List.of(r));
        assertThat(res.entries().get(0).op()).isEqualTo("MOVED");
        assertThat(res.summary().moved()).isEqualTo(1);
        assertThat(res.summary().changed()).isEqualTo(0);
    }

    @Test
    void mixed_change_with_parent_key_stays_CHANGED() {
        DiffRow r = row("CHANGED", "[\"X\"]",
                "{\"label_ru\":\"a\",\"parent_key\":[\"P1\"],\"attributes\":{}}",
                "{\"label_ru\":\"b\",\"parent_key\":[\"P2\"],\"attributes\":{}}");
        var res = differ.compute("1.0.0", "1.1.0", List.of(r));
        assertThat(res.entries().get(0).op()).isEqualTo("CHANGED");
        assertThat(res.entries().get(0).changedFields())
                .containsExactlyInAnyOrder("label_ru", "parent_key");
    }

    @Test
    void empty_input_yields_empty_summary() {
        var res = differ.compute("1.0.0", "1.0.0", List.of());
        assertThat(res.entries()).isEmpty();
        assertThat(res.summary().added()).isZero();
        assertThat(res.summary().changed()).isZero();
        assertThat(res.summary().removed()).isZero();
        assertThat(res.summary().moved()).isZero();
    }

    private static DiffRow row(String op, String keyJson, String beforeJson, String afterJson) {
        return new DiffRow(op, keyJson, beforeJson, afterJson);
    }

    private static String doc(String labelExpr) {
        return "{\"label_ru\":" + labelExpr + ",\"attributes\":{}}";
    }
}
