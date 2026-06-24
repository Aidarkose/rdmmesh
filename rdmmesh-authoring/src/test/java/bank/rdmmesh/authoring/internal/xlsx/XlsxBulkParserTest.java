package bank.rdmmesh.authoring.internal.xlsx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import bank.rdmmesh.authoring.internal.csv.CsvBulkParser;

/**
 * XLSX-парсер обязан вести себя идентично CSV-парсеру (тот же row-builder).
 * Фикстуры собираются fastexcel-writer'ом в памяти, без бинарных ресурсов.
 */
class XlsxBulkParserTest {

    private final XlsxBulkParser parser = new XlsxBulkParser(new ObjectMapper());

    @Test
    void parses_single_keyed_xlsx_with_label_columns() throws IOException {
        byte[] xlsx = workbook(sheet -> {
            header(sheet, "key_parts", "label_ru", "label_en");
            row(sheet, 1, "KZ", "Казахстан", "Kazakhstan");
            row(sheet, 2, "RU", "Россия", "Russia");
        });
        List<CsvBulkParser.Row> rows = parser.parse(in(xlsx));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).keyParts()).containsExactly("KZ");
        assertThat(rows.get(0).labelRu()).isEqualTo("Казахстан");
        assertThat(rows.get(1).labelEn()).isEqualTo("Russia");
    }

    @Test
    void parses_composite_key_and_attr_columns_stay_strings() throws IOException {
        byte[] xlsx = workbook(sheet -> {
            header(sheet, "key_parts", "attr.pd");
            row(sheet, 1, "[\"RETAIL\",\"BB\",\"12M\"]", "0.05");
        });
        List<CsvBulkParser.Row> rows = parser.parse(in(xlsx));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).keyParts()).containsExactly("RETAIL", "BB", "12M");
        assertThat(rows.get(0).attributes()).containsEntry("pd", "0.05");
    }

    @Test
    void parses_inline_attributes_json_preserving_number_types() throws IOException {
        byte[] xlsx = workbook(sheet -> {
            header(sheet, "key_parts", "attributes");
            row(sheet, 1, "1", "{\"stage\":\"1\",\"pd\":0.01}");
        });
        List<CsvBulkParser.Row> rows = parser.parse(in(xlsx));
        assertThat(rows.get(0).attributes()).containsEntry("stage", "1");
        assertThat(rows.get(0).attributes()).containsEntry("pd", 0.01);
    }

    @Test
    void skips_fully_blank_filler_rows() throws IOException {
        byte[] xlsx = workbook(sheet -> {
            header(sheet, "key_parts", "label_ru");
            row(sheet, 1, "A", "Альфа");
            // строка 2 намеренно пустая
            row(sheet, 3, "B", "Бета");
        });
        List<CsvBulkParser.Row> rows = parser.parse(in(xlsx));
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).keyParts()).containsExactly("A");
        assertThat(rows.get(1).keyParts()).containsExactly("B");
    }

    @Test
    void missing_key_parts_is_rejected_with_row_index() throws IOException {
        byte[] xlsx = workbook(sheet -> {
            header(sheet, "key_parts", "label_ru");
            row(sheet, 1, "", "без ключа");
        });
        assertThatThrownBy(() -> parser.parse(in(xlsx)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key_parts");
    }

    @Test
    void matches_header_case_insensitively_and_strips_nbsp_bom() throws IOException {
        // "Key_Parts" другой регистр; в label_ru — NBSP и BOM вокруг имени.
        byte[] xlsx = workbook(sheet -> {
            header(sheet, "Key_Parts", "\uFEFFlabel_ru\u00A0");
            row(sheet, 1, "KZ", "Казахстан");
        });
        List<CsvBulkParser.Row> rows = parser.parse(in(xlsx));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).keyParts()).containsExactly("KZ");
        assertThat(rows.get(0).labelRu()).isEqualTo("Казахстан");
    }

    @Test
    void picks_data_sheet_even_if_not_first() throws IOException {
        byte[] xlsx = multiSheet(
                sheet("Инструкция", s -> {
                    s.value(0, 0, "Как заполнять");
                    s.value(1, 0, "ля-ля");
                }),
                sheet("data", s -> {
                    s.value(0, 0, "key_parts");
                    s.value(0, 1, "label_ru");
                    s.value(1, 0, "S1");
                    s.value(1, 1, "Стадия 1");
                }));
        List<CsvBulkParser.Row> rows = parser.parse(in(xlsx));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).keyParts()).containsExactly("S1");
    }

    @Test
    void no_key_parts_anywhere_throws_actionable_error_listing_sheets() throws IOException {
        byte[] xlsx = workbook(sheet -> {
            header(sheet, "Код", "Наименование");
            row(sheet, 1, "X", "Икс");
        });
        assertThatThrownBy(() -> parser.parse(in(xlsx)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("key_parts")
                .hasMessageContaining("items") // имя листа
                .hasMessageContaining("Код"); // фактически найденные заголовки
    }

    // ── fastexcel-writer helpers ────────────────────────────────────────────────

    private interface SheetFiller {
        void fill(Worksheet sheet);
    }

    private record NamedSheet(String name, SheetFiller filler) {}

    private static NamedSheet sheet(String name, SheetFiller filler) {
        return new NamedSheet(name, filler);
    }

    private static byte[] multiSheet(NamedSheet... defs) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (Workbook wb = new Workbook(bos, "rdmmesh-test", "1.0")) {
            for (NamedSheet d : defs) {
                Worksheet ws = wb.newWorksheet(d.name());
                d.filler().fill(ws);
            }
        }
        return bos.toByteArray();
    }

    private static byte[] workbook(SheetFiller filler) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (Workbook wb = new Workbook(bos, "rdmmesh-test", "1.0")) {
            Worksheet ws = wb.newWorksheet("items");
            filler.fill(ws);
        }
        return bos.toByteArray();
    }

    private static void header(Worksheet ws, String... cols) {
        for (int c = 0; c < cols.length; c++) ws.value(0, c, cols[c]);
    }

    private static void row(Worksheet ws, int r, String... cells) {
        for (int c = 0; c < cells.length; c++) {
            if (!cells[c].isEmpty()) ws.value(r, c, cells[c]);
        }
    }

    private static InputStream in(byte[] b) {
        return new ByteArrayInputStream(b);
    }
}
