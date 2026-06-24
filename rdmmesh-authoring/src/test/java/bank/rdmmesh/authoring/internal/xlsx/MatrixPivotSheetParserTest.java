package bank.rdmmesh.authoring.internal.xlsx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import bank.rdmmesh.authoring.internal.xlsx.MatrixPivotSheetParser.PivotParseResult;
import bank.rdmmesh.authoring.internal.xlsx.MatrixPivotSheetParser.RowResidualPolicy;

/**
 * MatrixPivotSheetParser (E19 Commit 3) — раскладывает pivot-матрицу в triples
 * {@code (from, to, horizon, probability)}. Тесты повторяют шаблон
 * {@link XlsxBulkParserTest}: фикстура XLSX собирается fastexcel-writer'ом в памяти.
 */
class MatrixPivotSheetParserTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void parses_customers_4x4_matrix_with_implicit_default_residual() throws IOException {
        // Матрица из постановки заказчика — суммы строк 0.999 / 0.998 / 0.980 / 0.910.
        // Невязки в IMPLICIT_DEFAULT должны лечь в колонку D как 0.001 / 0.002 / 0.020 / 0.090.
        byte[] xlsx = workbook(sheet -> {
            header(sheet, "", "AAA", "A", "BB", "B");
            numRow(sheet, 1, "AAA", 0.880, 0.112, 0.006, 0.001);
            numRow(sheet, 2, "A",   0.023, 0.865, 0.095, 0.015);
            numRow(sheet, 3, "BB",  0.001, 0.050, 0.802, 0.127);
            numRow(sheet, 4, "B",   0.000, 0.005, 0.185, 0.720);
        });

        MatrixPivotSheetParser parser = new MatrixPivotSheetParser(json, "1Y", RowResidualPolicy.IMPLICIT_DEFAULT);
        PivotParseResult result = parser.parse(in(xlsx));

        // 16 explicit cells + 4 implicit D-cells (одна на каждую строку из 4-х).
        assertThat(result.rows()).hasSize(20);
        assertThat(result.implicitDefaultAdded()).isEqualTo(4);

        // Sample: проверяем, что AAA→AAA = 0.880 (key_parts=["AAA","AAA","1Y"]).
        assertThat(result.rows())
                .filteredOn(r -> r.keyParts().equals(java.util.List.of("AAA", "AAA", "1Y")))
                .hasSize(1)
                .first()
                .satisfies(r -> assertThat(r.attributes().get("probability"))
                        .isEqualTo(0.880));

        // Residual D-колонка для row AAA: 1 - 0.999 = 0.001.
        assertThat(result.rows())
                .filteredOn(r -> r.keyParts().equals(java.util.List.of("AAA", "D", "1Y")))
                .hasSize(1)
                .first()
                .satisfies(r -> {
                    double p = ((Number) r.attributes().get("probability")).doubleValue();
                    assertThat(p).isCloseTo(0.001, org.assertj.core.data.Offset.offset(1e-6));
                });

        // Residual D-колонка для row B: 1 - 0.910 = 0.090 (самый большой PD).
        assertThat(result.rows())
                .filteredOn(r -> r.keyParts().equals(java.util.List.of("B", "D", "1Y")))
                .hasSize(1)
                .first()
                .satisfies(r -> {
                    double p = ((Number) r.attributes().get("probability")).doubleValue();
                    assertThat(p).isCloseTo(0.090, org.assertj.core.data.Offset.offset(1e-6));
                });
    }

    @Test
    void already_stochastic_matrix_does_not_add_implicit_default() throws IOException {
        // 2×2 стохастичная (Σ=1 в каждой строке): IMPLICIT_DEFAULT ничего не добавляет.
        byte[] xlsx = workbook(sheet -> {
            header(sheet, "", "X", "Y");
            numRow(sheet, 1, "X", 0.7, 0.3);
            numRow(sheet, 2, "Y", 0.4, 0.6);
        });
        PivotParseResult result =
                new MatrixPivotSheetParser(json, "1Y", RowResidualPolicy.IMPLICIT_DEFAULT)
                        .parse(in(xlsx));
        assertThat(result.rows()).hasSize(4);
        assertThat(result.implicitDefaultAdded()).isZero();
    }

    @Test
    void strict_policy_rejects_non_stochastic_row_with_actionable_message() throws IOException {
        byte[] xlsx = workbook(sheet -> {
            header(sheet, "", "X", "Y");
            numRow(sheet, 1, "X", 0.7, 0.2); // Σ=0.9, не стохастична
        });
        assertThatThrownBy(() ->
                        new MatrixPivotSheetParser(json, "1Y", RowResidualPolicy.STRICT).parse(in(xlsx)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from='X'")
                .hasMessageContaining("не стохастична")
                .hasMessageContaining("STRICT");
    }

    @Test
    void row_sum_above_one_is_rejected_even_with_implicit_default() throws IOException {
        // residual < 0 → IMPLICIT_DEFAULT неприменим (нечего дописывать).
        byte[] xlsx = workbook(sheet -> {
            header(sheet, "", "X", "Y");
            numRow(sheet, 1, "X", 0.7, 0.5); // Σ=1.2
        });
        assertThatThrownBy(() ->
                        new MatrixPivotSheetParser(json, "1Y", RowResidualPolicy.IMPLICIT_DEFAULT).parse(in(xlsx)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("from='X'")
                .hasMessageContaining("превышает 1");
    }

    @Test
    void horizon_is_required() {
        assertThatThrownBy(() -> new MatrixPivotSheetParser(json, "", RowResidualPolicy.IMPLICIT_DEFAULT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("horizon");
        assertThatThrownBy(() -> new MatrixPivotSheetParser(json, null, RowResidualPolicy.IMPLICIT_DEFAULT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("horizon");
    }

    @Test
    void policy_string_parsing_normalizes_variants() {
        assertThat(RowResidualPolicy.parseOrDefault(null)).isEqualTo(RowResidualPolicy.IMPLICIT_DEFAULT);
        assertThat(RowResidualPolicy.parseOrDefault("")).isEqualTo(RowResidualPolicy.IMPLICIT_DEFAULT);
        assertThat(RowResidualPolicy.parseOrDefault("implicit_default")).isEqualTo(RowResidualPolicy.IMPLICIT_DEFAULT);
        assertThat(RowResidualPolicy.parseOrDefault("Strict")).isEqualTo(RowResidualPolicy.STRICT);
        assertThat(RowResidualPolicy.parseOrDefault("free")).isEqualTo(RowResidualPolicy.FREE);
        assertThat(RowResidualPolicy.parseOrDefault("FREE_FORM")).isEqualTo(RowResidualPolicy.FREE);
        assertThat(RowResidualPolicy.parseOrDefault("none")).isEqualTo(RowResidualPolicy.FREE);
        assertThatThrownBy(() -> RowResidualPolicy.parseOrDefault("foo"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void free_policy_accepts_non_stochastic_delinquency_matrix() throws IOException {
        // Пример заказчика — DPD flow-matrix: суммы строк {0.3, 0.0029, 0.0029, 2.41, 2.1}.
        // С IMPLICIT_DEFAULT/STRICT строки 4-5 упали бы (Σ>1), но FREE их пропускает.
        byte[] xlsx = workbook(sheet -> {
            header(sheet, "", "0d", "1-30d", "31-90d", "91+d", "closed");
            numRow(sheet, 1, "0d",     0.200, 0.100, 0.000, 0.000, 0.000); // Σ=0.300
            numRow(sheet, 2, "1-30d",  0.002, 0.0008, 0.0001, 0.000, 0.000); // Σ=0.0029
            numRow(sheet, 3, "31-90d", 0.002, 0.0008, 0.0001, 0.000, 0.000); // Σ=0.0029
            numRow(sheet, 4, "91+d",   0.010, 0.600, 0.800, 1.000, 0.000); // Σ=2.41
            numRow(sheet, 5, "closed", 0.700, 0.300, 0.100, 0.000, 1.000); // Σ=2.10
        });
        PivotParseResult result =
                new MatrixPivotSheetParser(json, "1M", RowResidualPolicy.FREE).parse(in(xlsx));
        // Пустые ячейки (=0.000) тоже создают triples в fastexcel — это норма для FREE.
        // Главное: residual-колонки D НЕ дописаны, ничего не упало.
        assertThat(result.implicitDefaultAdded()).isZero();
        assertThat(result.rows()).isNotEmpty();
        // Sample: проверяем «острую» ячейку 91+d → 91+d = 1.000.
        assertThat(result.rows())
                .filteredOn(r -> r.keyParts().equals(java.util.List.of("91+d", "91+d", "1M")))
                .hasSize(1)
                .first()
                .satisfies(r -> assertThat(((Number) r.attributes().get("probability")).doubleValue())
                        .isEqualTo(1.000));
        // Sample: closed → closed = 1.000 (absorbing).
        assertThat(result.rows())
                .filteredOn(r -> r.keyParts().equals(java.util.List.of("closed", "closed", "1M")))
                .hasSize(1)
                .first()
                .satisfies(r -> assertThat(((Number) r.attributes().get("probability")).doubleValue())
                        .isEqualTo(1.000));
    }

    @Test
    void empty_first_cell_skips_filler_row() throws IOException {
        // Строка с пустой first-cell (label оси) — игнорируется.
        byte[] xlsx = workbook(sheet -> {
            header(sheet, "", "X", "Y");
            numRow(sheet, 1, "X", 0.5, 0.5);
            // строка 2 пустая
            numRow(sheet, 3, "Y", 0.5, 0.5);
        });
        PivotParseResult result =
                new MatrixPivotSheetParser(json, "1Y", RowResidualPolicy.IMPLICIT_DEFAULT).parse(in(xlsx));
        assertThat(result.rows()).hasSize(4);
        assertThat(result.implicitDefaultAdded()).isZero();
    }

    @Test
    void empty_cell_within_row_is_skipped_not_treated_as_zero() throws IOException {
        // Если ячейка пустая — triple НЕ создаётся. Это важно для частичных матриц
        // (например, transition только между соседними грейдами). residual всё равно
        // вычисляется по тому, что есть; пустые ячейки не считаются как 0.
        byte[] xlsx = workbook(sheet -> {
            header(sheet, "", "X", "Y");
            sheet.value(1, 0, "X");
            sheet.value(1, 1, 0.4);
            // cell (1,2) — пустая
        });
        PivotParseResult result =
                new MatrixPivotSheetParser(json, "1Y", RowResidualPolicy.IMPLICIT_DEFAULT).parse(in(xlsx));
        // Σ=0.4, residual=0.6 → дописана D-ячейка.
        assertThat(result.rows()).hasSize(2);
        assertThat(result.implicitDefaultAdded()).isOne();
    }

    @Test
    void out_of_range_probability_is_rejected() throws IOException {
        byte[] xlsx = workbook(sheet -> {
            header(sheet, "", "X");
            numRow(sheet, 1, "X", 1.5);
        });
        assertThatThrownBy(() ->
                        new MatrixPivotSheetParser(json, "1Y", RowResidualPolicy.IMPLICIT_DEFAULT).parse(in(xlsx)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("вне [0, 1]");
    }

    // ── fastexcel-writer helpers (повторяют XlsxBulkParserTest) ──────────────────

    private interface SheetFiller {
        void fill(Worksheet sheet);
    }

    private static byte[] workbook(SheetFiller filler) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (Workbook wb = new Workbook(bos, "rdmmesh-test", "1.0")) {
            Worksheet sheet = wb.newWorksheet("items");
            filler.fill(sheet);
        }
        return bos.toByteArray();
    }

    private static void header(Worksheet sheet, String... cols) {
        for (int c = 0; c < cols.length; c++) sheet.value(0, c, cols[c]);
    }

    private static void numRow(Worksheet sheet, int rowIdx, String label, double... values) {
        sheet.value(rowIdx, 0, label);
        for (int c = 0; c < values.length; c++) sheet.value(rowIdx, c + 1, values[c]);
    }

    private static InputStream in(byte[] xlsx) {
        return new ByteArrayInputStream(xlsx);
    }
}
