package bank.rdmmesh.distribution.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;

import com.fasterxml.jackson.databind.ObjectMapper;

import bank.rdmmesh.distribution.internal.service.DistributionService.ItemDto;

/**
 * XLSX bulk-export. Фиксированные колонки ({@code key_parts, parent_key, label,
 * description, order_index, status, effective_from, effective_to}) совпадают с
 * CSV-экспортом, чтобы downstream-инструменты потребляли xlsx и csv взаимозаменяемо.
 *
 * <p>E24: атрибуты записи <b>разворачиваются в отдельные колонки</b> {@code attr.<имя>}
 * в порядке, заданном схемой справочника ({@code propertyOrder}, см.
 * {@code DistributionService#resolveAttributeOrder}). Атрибуты, встретившиеся в данных,
 * но не указанные в порядке, дописываются в конец (отсортированными) — чтобы не терять
 * данные при рассинхроне схемы и записей. Последней колонкой сохраняется
 * {@code attributes} (JSON-строка): {@code attr.*} при импорте коэрсит значения в
 * строку/булево, поэтому числовые/типизированные атрибуты корректно round-trip'ятся
 * только через JSON-колонку (см. {@code docs/import-spravochnikov.md} §4).
 *
 * <p>{@code key_parts}/{@code parent_key}/{@code attributes} сериализуются JSON-строкой
 * в одну ячейку (как в CSV).
 *
 * <p>fastexcel-writer стримит книгу через opczip напрямую в {@link OutputStream} —
 * нет DOM-материализации, экспорт десятков тысяч строк держится в скромном heap'е.
 */
public final class XlsxExporter {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Фиксированные колонки слева, до развёрнутых attr.*-колонок. */
    private static final String[] FIXED_COLUMNS = {
        "key_parts",
        "parent_key",
        "label",
        "description",
        "order_index",
        "status",
        "effective_from",
        "effective_to"
    };

    private XlsxExporter() {}

    public static void write(List<ItemDto> items, List<String> attributeOrder, OutputStream out)
            throws IOException {
        List<String> attrNames = resolveAttrColumns(items, attributeOrder);
        try (Workbook wb = new Workbook(out, "rdmmesh", "1.0")) {
            Worksheet ws = wb.newWorksheet("items");

            // header
            int c = 0;
            for (String col : FIXED_COLUMNS) {
                ws.value(0, c, col);
                ws.style(0, c).bold().set();
                c++;
            }
            for (String name : attrNames) {
                ws.value(0, c, "attr." + name);
                ws.style(0, c).bold().set();
                c++;
            }
            ws.value(0, c, "attributes");
            ws.style(0, c).bold().set();

            // rows
            int r = 1;
            for (ItemDto i : items) {
                int col = 0;
                ws.value(r, col++, writeJson(i.keyParts()));
                ws.value(r, col++, i.parentKey() == null ? null : writeJson(i.parentKey()));
                ws.value(r, col++, i.label());
                ws.value(r, col++, i.description());
                ws.value(r, col++, i.orderIndex());
                ws.value(r, col++, i.status());
                ws.value(r, col++, i.effectiveFrom());
                ws.value(r, col++, i.effectiveTo());
                Map<String, Object> attrs = i.attributes();
                for (String name : attrNames) {
                    attrCell(ws, r, col++, attrs == null ? null : attrs.get(name));
                }
                ws.value(r, col, writeJson(attrs));
                r++;
            }
        }
    }

    /**
     * Итоговый список attr-колонок: сначала имена из {@code attributeOrder}, затем
     * атрибуты из данных, которых нет в порядке (отсортированные, для детерминизма).
     */
    private static List<String> resolveAttrColumns(List<ItemDto> items, List<String> attributeOrder) {
        LinkedHashSet<String> cols = new LinkedHashSet<>();
        if (attributeOrder != null) cols.addAll(attributeOrder);
        TreeSet<String> extras = new TreeSet<>();
        for (ItemDto i : items) {
            Map<String, Object> attrs = i.attributes();
            if (attrs == null) continue;
            for (String k : attrs.keySet()) {
                if (!cols.contains(k)) extras.add(k);
            }
        }
        cols.addAll(extras);
        return new ArrayList<>(cols);
    }

    /** Типобезопасная запись значения атрибута: число/булево сохраняют тип, прочее — строкой. */
    private static void attrCell(Worksheet ws, int r, int c, Object v) {
        if (v == null) {
            return;
        } else if (v instanceof Number n) {
            ws.value(r, c, n);
        } else if (v instanceof Boolean b) {
            ws.value(r, c, b);
        } else if (v instanceof String s) {
            ws.value(r, c, s);
        } else {
            ws.value(r, c, writeJson(v));
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
