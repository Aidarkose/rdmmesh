package bank.rdmmesh.distribution.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import org.junit.jupiter.api.Test;

import bank.rdmmesh.distribution.internal.service.DistributionService.ItemDto;

/** Экспорт в xlsx читается обратно fastexcel-reader'ом с тем же набором колонок, что CSV. */
class XlsxExporterTest {

    @Test
    void exports_items_with_csv_compatible_columns() throws IOException {
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
        XlsxExporter.write(items, bos);

        List<List<String>> grid = read(bos.toByteArray());
        assertThat(grid.get(0))
                .containsExactly(
                        "key_parts",
                        "parent_key",
                        "label",
                        "description",
                        "attributes",
                        "order_index",
                        "status",
                        "effective_from",
                        "effective_to");
        // строка 1: S1
        assertThat(grid.get(1).get(0)).isEqualTo("[\"S1\"]");
        assertThat(grid.get(1).get(2)).isEqualTo("Стадия 1");
        assertThat(grid.get(1).get(4)).isEqualTo("{\"stage\":\"1\"}");
        assertThat(grid.get(1).get(6)).isEqualTo("ACTIVE");
        assertThat(grid.get(1).get(7)).isEqualTo("2026-01-01");
        // строка 2: composite key + parent_key
        assertThat(grid.get(2).get(0)).isEqualTo("[\"RETAIL\",\"BB\",\"12M\"]");
        assertThat(grid.get(2).get(1)).isEqualTo("[\"RETAIL\"]");
    }

    @Test
    void exports_empty_item_list_with_header_only() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        XlsxExporter.write(List.of(), bos);
        List<List<String>> grid = read(bos.toByteArray());
        assertThat(grid).hasSize(1);
        assertThat(grid.get(0).get(0)).isEqualTo("key_parts");
    }

    private static List<List<String>> read(byte[] xlsx) throws IOException {
        List<List<String>> grid = new ArrayList<>();
        try (ReadableWorkbook wb = new ReadableWorkbook(new ByteArrayInputStream(xlsx))) {
            Sheet sheet = wb.getFirstSheet();
            for (Row row : sheet.read()) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < 9; c++) {
                    String t = row.getCellText(c);
                    cells.add(t == null ? "" : t);
                }
                grid.add(cells);
            }
        }
        return grid;
    }
}
