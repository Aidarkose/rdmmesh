package bank.rdmmesh.distribution.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

import com.fasterxml.jackson.databind.ObjectMapper;

import bank.rdmmesh.distribution.internal.service.DistributionService.ItemDto;

/**
 * XLSX bulk-export (новая фича: экспорт справочников в Excel). Набор колонок —
 * <b>тот же, что у CSV-экспорта</b> ({@code key_parts, parent_key, label,
 * description, attributes, order_index, status, effective_from, effective_to}),
 * чтобы downstream-инструменты могли потреблять xlsx и csv взаимозаменяемо.
 *
 * <p>{@code key_parts}/{@code parent_key}/{@code attributes} сериализуются JSON-строкой
 * в одну ячейку (как в CSV) — разворачивание attributes по колонкам остаётся V1+
 * (требует resolve активной CodeSetSchema; тот же debt, что у CSV-экспорта, см.
 * handoff E8 §4).
 *
 * <p>fastexcel-writer стримит книгу через opczip напрямую в {@link OutputStream} —
 * нет DOM-материализации, экспорт десятков тысяч строк держится в скромном heap'е.
 */
public final class XlsxExporter {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String[] COLUMNS = {
        "key_parts",
        "parent_key",
        "label",
        "description",
        "attributes",
        "order_index",
        "status",
        "effective_from",
        "effective_to"
    };

    private XlsxExporter() {}

    public static void write(List<ItemDto> items, OutputStream out) throws IOException {
        try (Workbook wb = new Workbook(out, "rdmmesh", "1.0")) {
            Worksheet ws = wb.newWorksheet("items");
            for (int c = 0; c < COLUMNS.length; c++) {
                ws.value(0, c, COLUMNS[c]);
                ws.style(0, c).bold().set();
            }
            int r = 1;
            for (ItemDto i : items) {
                ws.value(r, 0, writeJson(i.keyParts()));
                ws.value(r, 1, i.parentKey() == null ? null : writeJson(i.parentKey()));
                ws.value(r, 2, i.label());
                ws.value(r, 3, i.description());
                ws.value(r, 4, writeJson(i.attributes()));
                ws.value(r, 5, i.orderIndex());
                ws.value(r, 6, i.status());
                ws.value(r, 7, i.effectiveFrom());
                ws.value(r, 8, i.effectiveTo());
                r++;
            }
        }
    }

    private static String writeJson(Object o) {
        try {
            return JSON.writeValueAsString(o);
        } catch (IOException e) {
            return String.valueOf(o);
        }
    }
}
