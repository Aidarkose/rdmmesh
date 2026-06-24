package bank.rdmmesh.authoring.internal.xlsx;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;

import bank.rdmmesh.authoring.internal.csv.CsvBulkParser;

/**
 * Парсер XLSX для bulk-import'а CodeItem'ов (фича: импорт справочников из Excel).
 *
 * <p>Контракт колонок — <b>тот же, что у {@link CsvBulkParser}</b>: строка-заголовок,
 * дальше — данные; обязательная колонка {@code key_parts}. Построение строки
 * делегируется в {@link CsvBulkParser#buildRow}, чтобы CSV и XLSX вели себя
 * идентично.
 *
 * <p><b>Устойчивость к реальным книгам Excel.</b> Пользователи приносят файлы, где
 * (а) первый лист — «Инструкция»/обложка, а данные на другом листе; (б) заголовок
 * набран в другом регистре или с лишними пробелами/NBSP/BOM. Поэтому:
 * <ul>
 *   <li>сканируются <b>все листы</b>; берётся первый, чья строка-заголовок (после
 *       нормализации) содержит {@code key_parts};</li>
 *   <li>имена известных колонок матчатся <b>регистронезависимо</b>, с обрезкой
 *       BOM/zero-width/NBSP; {@code attr.<name>} сохраняет регистр имени атрибута;</li>
 *   <li>если ни на одном листе нет {@code key_parts} — бросается понятная ошибка
 *       со списком листов и фактически найденных заголовков (а не невнятное
 *       «Row 0: column 'key_parts' is required»).</li>
 * </ul>
 *
 * <p>Чтение — стримовое (fastexcel-reader на StAX), без DOM-материализации.
 */
public final class XlsxBulkParser {

    // Невидимые символы, которые Excel/копипаст могут занести в заголовок
    // (заданы unicode-escape'ами, чтобы в исходнике не было «невидимок»).
    private static final char NBSP = '\u00A0';
    private static final String BOM = "\uFEFF";
    private static final String ZERO_WIDTH_SPACE = "\u200B";

    /** Канонические (lower-case) имена фиксированных колонок контракта. */
    private static final Set<String> KNOWN = Set.of(
            "key_parts",
            "parent_key",
            "label_ru",
            "label_en",
            "description_ru",
            "description_en",
            "attributes",
            "order_index",
            "status",
            "effective_from",
            "effective_to");

    private final ObjectMapper json;

    public XlsxBulkParser(ObjectMapper json) {
        this.json = json;
    }

    public List<CsvBulkParser.Row> parse(InputStream in) throws IOException {
        try (ReadableWorkbook wb = new ReadableWorkbook(in)) {
            List<Sheet> sheets = wb.getSheets().toList();
            if (sheets.isEmpty()) {
                throw new IllegalArgumentException("XLSX-книга не содержит ни одного листа");
            }
            // Диагностика на случай промаха — что реально нашли на каждом листе.
            StringBuilder seen = new StringBuilder();
            for (Sheet sheet : sheets) {
                try (Stream<Row> rows = sheet.openStream()) {
                    Iterator<Row> it = rows.iterator();
                    List<String> header = it.hasNext() ? canonHeader(it.next()) : List.of();
                    if (header.contains("key_parts")) {
                        return readData(header, it);
                    }
                    if (seen.length() > 0) seen.append("; ");
                    seen.append('\'')
                            .append(sheet.getName())
                            .append("': ")
                            .append(header.isEmpty()
                                    ? "(пустой заголовок)"
                                    : String.join(", ", header));
                }
            }
            throw new IllegalArgumentException(
                    "Ни на одном листе нет обязательной колонки 'key_parts'. "
                            + "Первая строка нужного листа должна содержать заголовки "
                            + "(как у CSV: key_parts[, label_ru, label_en, attributes, ...]). "
                            + "Найдено по листам — " + seen);
        }
    }

    private List<CsvBulkParser.Row> readData(List<String> header, Iterator<Row> it) {
        List<CsvBulkParser.Row> out = new ArrayList<>();
        int dataRowIndex = 0;
        while (it.hasNext()) {
            Map<String, String> raw = readRow(header, it.next());
            if (raw.values().stream().allMatch(v -> v == null || v.isBlank())) {
                continue; // пустая строка-заполнитель — пропускаем
            }
            out.add(CsvBulkParser.buildRow(json, raw, dataRowIndex));
            dataRowIndex++;
        }
        return out;
    }

    /** Заголовок листа → список нормализованных имён колонок (с обрезкой пустого хвоста). */
    private static List<String> canonHeader(Row row) {
        List<String> header = new ArrayList<>();
        int cells = row.getCellCount();
        for (int c = 0; c < cells; c++) {
            header.add(canon(row.getCellText(c)));
        }
        while (!header.isEmpty() && header.get(header.size() - 1).isEmpty()) {
            header.remove(header.size() - 1);
        }
        return header;
    }

    private static Map<String, String> readRow(List<String> header, Row row) {
        Map<String, String> raw = new LinkedHashMap<>();
        for (int c = 0; c < header.size(); c++) {
            String name = header.get(c);
            if (name.isEmpty()) continue;
            String t = row.getCellText(c);
            raw.put(name, t == null ? "" : t.trim());
        }
        return raw;
    }

    /**
     * Нормализация имени колонки: NBSP→space, убрать BOM/zero-width, trim; известные
     * колонки привести к каноническому lower-case; {@code attr.<name>} — префикс в
     * lower-case, имя атрибута как есть (регистр payload'а значим); прочее — trimmed.
     */
    private static String canon(String raw) {
        if (raw == null) return "";
        String v = raw.replace(NBSP, ' ')
                .replace(BOM, "")
                .replace(ZERO_WIDTH_SPACE, "")
                .trim();
        if (v.isEmpty()) return "";
        String lower = v.toLowerCase(Locale.ROOT);
        if (KNOWN.contains(lower)) return lower;
        if (lower.startsWith("attr.")) return "attr." + v.substring(5).trim();
        return v;
    }
}
