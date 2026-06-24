package bank.rdmmesh.distribution.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.Test;

import bank.rdmmesh.distribution.internal.service.DistributionService.ItemDto;

/** Экспорт в xlsx читается обратно fastexcel-reader'ом; E24 — attr.*-колонки в порядке схемы. */
class XlsxExporterTest {

    @Test
    void expands_attributes_into_attr_columns_in_schema_order() throws IOException {
        List<ItemDto> items = List.of(
                new ItemDto(
                        List.of("S1"), null, "Стадия 1", null, Map.of("stage", "1"), 0, "ACTIVE", "2026-01-01", null),
                new ItemDto(
                        List.of("RETAIL", "BB", "12M"),
                        List.of("RETAIL"),
                        "Розница BB 12М",
                        "desc",
                        Map.of("pd", 0.0234),
                        1,
                        "ACTIVE",
                        null,
                        null));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        // порядок из схемы: pd раньше stage
        XlsxExporter.write(items, List.of("pd", "stage"), bos);

        List<List<String>> grid = read(bos.toByteArray(), 11);
        assertThat(grid.get(0))
                .containsExactly(
                        "key_parts",
                        "parent_key",
                        "label",
                        "description",
                        "order_index",
                        "status",
                        "effective_from",
                        "effective_to",
                        "attr.pd",
                        "attr.stage",
                        "attributes");
        // строка 1: S1 — stage="1" в attr.stage (col 9), attr.pd (col 8) пустой
        assertThat(grid.get(1).get(0)).isEqualTo("[\"S1\"]");
        assertThat(grid.get(1).get(2)).isEqualTo("Стадия 1");
        assertThat(grid.get(1).get(5)).isEqualTo("ACTIVE");
        assertThat(grid.get(1).get(6)).isEqualTo("2026-01-01");
        assertThat(grid.get(1).get(8)).isEmpty();
        assertThat(grid.get(1).get(9)).isEqualTo("1");
        assertThat(grid.get(1).get(10)).isEqualTo("{\"stage\":\"1\"}");
        // строка 2: composite key + parent_key; pd=0.0234 числом в attr.pd (col 8)
        assertThat(grid.get(2).get(0)).isEqualTo("[\"RETAIL\",\"BB\",\"12M\"]");
        assertThat(grid.get(2).get(1)).isEqualTo("[\"RETAIL\"]");
        assertThat(grid.get(2).get(8)).isEqualTo("0.0234");
        assertThat(grid.get(2).get(9)).isEmpty();
        assertThat(grid.get(2).get(10)).isEqualTo("{\"pd\":0.0234}");
    }

    @Test
    void unordered_attributes_fall_back_to_sorted_columns() throws IOException {
        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("zeta", "z");
        attrs.put("alpha", "a");
        List<ItemDto> items =
                List.of(new ItemDto(List.of("S1"), null, "L", null, attrs, 0, "ACTIVE", null, null));

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        // порядок не задан → колонки отсортированы по алфавиту
        XlsxExporter.write(items, List.of(), bos);

        List<List<String>> grid = read(bos.toByteArray(), 11);
        assertThat(grid.get(0).get(8)).isEqualTo("attr.alpha");
        assertThat(grid.get(0).get(9)).isEqualTo("attr.zeta");
        assertThat(grid.get(1).get(8)).isEqualTo("a");
        assertThat(grid.get(1).get(9)).isEqualTo("z");
    }

    @Test
    void exports_empty_item_list_with_header_only() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        XlsxExporter.write(List.of(), List.of(), bos);
        List<List<String>> grid = read(bos.toByteArray(), 9);
        assertThat(grid).hasSize(1);
        assertThat(grid.get(0).get(0)).isEqualTo("key_parts");
        assertThat(grid.get(0).get(8)).isEqualTo("attributes");
    }

    private static List<List<String>> read(byte[] xlsx, int width) throws IOException {
        List<List<String>> grid = new ArrayList<>();
        try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getFirstSheet();
            for (Row row : sheet.read()) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < width; c++) {
                    String t = row.getCellText(c);
                    cells.add(t == null ? "" : t);
                }
                grid.add(cells);
            }
        }
        return grid;
    }
}
