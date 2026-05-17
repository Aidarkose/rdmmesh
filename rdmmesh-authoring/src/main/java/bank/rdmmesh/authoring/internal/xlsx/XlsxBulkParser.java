package bank.rdmmesh.authoring.internal.xlsx;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;

import com.fasterxml.jackson.databind.ObjectMapper;

import bank.rdmmesh.authoring.internal.csv.CsvBulkParser;

/**
 * Парсер XLSX для bulk-import'а CodeItem'ов (новая фича: импорт справочников из Excel).
 *
 * <p>Контракт колонок — <b>тот же, что у {@link CsvBulkParser}</b>: первая строка
 * первого листа книги — заголовок, дальше — данные. Поддерживаемые колонки и их
 * семантика (включая {@code key_parts} как JSON-array, {@code attr.<name>} с той же
 * коэрцией, ISO-даты) описаны в Javadoc {@link CsvBulkParser}. Реализация делегирует
 * построение строки в {@link CsvBulkParser#buildRow}, чтобы CSV и XLSX вели себя
 * идентично и не разъезжались в обработке ошибок.
 *
 * <p>Чтение — стримовое (fastexcel-reader на StAX), книга не материализуется в DOM:
 * крупные справочники не дают OOM (риск из SPEC §5.3 «большой draft → OOM»).
 *
 * <p>Значение ячейки берётся как отображаемый текст ({@link Row#getCellText(int)}) —
 * это совпадает с тем, что пользователь видит в Excel, ровно как CSV хранит то, что
 * было набрано. Полностью пустые строки (например, форматирование «в запас») —
 * пропускаются, а не падают на отсутствии {@code key_parts}.
 */
public final class XlsxBulkParser {

    private final ObjectMapper json;

    public XlsxBulkParser(ObjectMapper json) {
        this.json = json;
    }

    public List<CsvBulkParser.Row> parse(InputStream in) throws IOException {
        List<CsvBulkParser.Row> out = new ArrayList<>();
        try (ReadableWorkbook wb = new ReadableWorkbook(in)) {
            Sheet sheet = wb.getFirstSheet();
            if (sheet == null) {
                throw new IllegalArgumentException("XLSX-книга не содержит ни одного листа");
            }
            try (Stream<Row> rows = sheet.openStream()) {
                List<String> header = null;
                int dataRowIndex = 0;
                for (Row row : (Iterable<Row>) rows::iterator) {
                    if (header == null) {
                        header = readHeader(row);
                        if (header.isEmpty()) {
                            throw new IllegalArgumentException(
                                    "Первая строка XLSX (заголовок) пуста — нужны имена колонок");
                        }
                        continue;
                    }
                    Map<String, String> raw = readRow(header, row);
                    if (raw.values().stream().allMatch(v -> v == null || v.isBlank())) {
                        continue; // пустая строка-заполнитель — пропускаем
                    }
                    out.add(CsvBulkParser.buildRow(json, raw, dataRowIndex));
                    dataRowIndex++;
                }
            }
        }
        return out;
    }

    private static List<String> readHeader(Row row) {
        List<String> header = new ArrayList<>();
        int cells = row.getCellCount();
        for (int c = 0; c < cells; c++) {
            header.add(text(row, c));
        }
        // отрезаем пустой «хвост» заголовка, чтобы trailing-колонки без имени
        // не порождали ключ "" в map'е строки.
        while (!header.isEmpty() && header.get(header.size() - 1).isBlank()) {
            header.remove(header.size() - 1);
        }
        return header;
    }

    private static Map<String, String> readRow(List<String> header, Row row) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (int c = 0; c < header.size(); c++) {
            String name = header.get(c);
            if (name.isBlank()) continue;
            raw.put(name, text(row, c));
        }
        return raw;
    }

    /** Отображаемый текст ячейки; отсутствующая ячейка → "" (как пустая CSV-колонка). */
    private static String text(Row row, int col) {
        String t = row.getCellText(col);
        return t == null ? "" : t.trim();
    }
}
