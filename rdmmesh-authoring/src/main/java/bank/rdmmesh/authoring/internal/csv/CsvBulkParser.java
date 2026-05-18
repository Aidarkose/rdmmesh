package bank.rdmmesh.authoring.internal.csv;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;

/**
 * Парсер CSV для bulk-import'а CodeItem'ов.
 *
 * <p>Формат — header-based CSV. Поддерживаемые колонки:
 * <ul>
 *   <li>{@code key_parts} (обязательная) — JSON-array as string, например {@code ["KZ"]}
 *       или {@code ["RETAIL","BB","12M"]}. Для одиночных ключей принимается также
 *       single-string без квадратных скобок (е.g. {@code KZ}).
 *   <li>{@code label_ru}, {@code label_en}, {@code description_ru}, {@code description_en}
 *   <li>{@code parent_key} — JSON-array as string, для intra-codeset hierarchy
 *   <li>{@code attributes} — JSON-object as string. Для UI-friendly импорта может
 *       расщепляться на {@code attr.<name>} (см. ниже).
 *   <li>Любая колонка с префиксом {@code attr.} попадает в attributes под этим именем
 *       (без префикса). Значения парсятся как: number, если выглядят как число,
 *       boolean (true/false), иначе string.
 *   <li>{@code order_index}, {@code status}, {@code effective_from}, {@code effective_to}
 * </ul>
 *
 * <p>Этот парсер только разбирает строки в типизированные {@link Row}, не валидирует
 * attributes по CodeSetSchema (это делает service); единственный жёсткий констрейнт
 * — наличие {@code key_parts} в каждой строке.
 */
public final class CsvBulkParser {

    private static final CsvMapper CSV_MAPPER = new CsvMapper();

    private final ObjectMapper json;

    public CsvBulkParser(ObjectMapper json) {
        this.json = json;
    }

    public List<Row> parse(InputStream in) throws IOException {
        CsvSchema schema = CsvSchema.emptySchema().withHeader();
        List<Row> out = new ArrayList<>();
        try (MappingIterator<Map<String, String>> it = CSV_MAPPER
                .readerFor(new TypeReference<Map<String, String>>() {})
                .with(schema)
                .readValues(in)) {
            int rowIndex = 0;
            while (it.hasNext()) {
                Map<String, String> raw = it.next();
                out.add(buildRow(json, raw, rowIndex));
                rowIndex++;
            }
        }
        return out;
    }

    /**
     * Преобразует «сырую» строку (header-имя колонки → строковое значение ячейки)
     * в типизированный {@link Row}. Вынесено в {@code public static}, чтобы тот же
     * контракт колонок/коэрции/ошибок переиспользовал XLSX-парсер (новая фича —
     * импорт справочников из Excel), не дублируя логику разбора {@code key_parts},
     * {@code attr.*} и дат.
     */
    public static Row buildRow(ObjectMapper json, Map<String, String> raw, int rowIndex) {
        String keyText = nullIfBlank(raw.get("key_parts"));
        if (keyText == null) {
            throw new IllegalArgumentException("Row " + rowIndex + ": column 'key_parts' is required");
        }
        List<String> keyParts;
        try {
            if (keyText.startsWith("[")) {
                keyParts = json.readValue(keyText, new TypeReference<List<String>>() {});
            } else {
                keyParts = List.of(keyText);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Row " + rowIndex + ": cannot parse key_parts '" + keyText + "': " + e.getMessage(), e);
        }

        List<String> parentKey = null;
        String parentText = nullIfBlank(raw.get("parent_key"));
        if (parentText != null) {
            try {
                parentKey = parentText.startsWith("[")
                        ? json.readValue(parentText, new TypeReference<List<String>>() {})
                        : List.of(parentText);
            } catch (IOException e) {
                throw new IllegalArgumentException(
                        "Row " + rowIndex + ": cannot parse parent_key '" + parentText + "': " + e.getMessage(), e);
            }
        }

        Map<String, Object> attributes = new LinkedHashMap<>();
        String attrText = nullIfBlank(raw.get("attributes"));
        if (attrText != null) {
            try {
                Map<String, Object> base = json.readValue(attrText, new TypeReference<Map<String, Object>>() {});
                attributes.putAll(base);
            } catch (IOException e) {
                throw new IllegalArgumentException(
                        "Row " + rowIndex + ": cannot parse attributes JSON: " + e.getMessage(), e);
            }
        }
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (e.getKey() != null && e.getKey().startsWith("attr.")) {
                String name = e.getKey().substring("attr.".length());
                String v = nullIfBlank(e.getValue());
                if (v == null) continue;
                attributes.put(name, coerce(v));
            }
        }

        Integer orderIndex = null;
        String orderText = nullIfBlank(raw.get("order_index"));
        if (orderText != null) {
            try {
                orderIndex = Integer.parseInt(orderText);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(
                        "Row " + rowIndex + ": order_index must be integer, got '" + orderText + "'");
            }
        }

        LocalDate effFrom = parseDate(raw.get("effective_from"), rowIndex, "effective_from");
        LocalDate effTo = parseDate(raw.get("effective_to"), rowIndex, "effective_to");

        return new Row(
                rowIndex,
                keyParts,
                parentKey,
                nullIfBlank(raw.get("label_ru")),
                nullIfBlank(raw.get("label_en")),
                nullIfBlank(raw.get("description_ru")),
                nullIfBlank(raw.get("description_en")),
                attributes,
                orderIndex,
                nullIfBlank(raw.get("status")),
                effFrom,
                effTo);
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /**
     * Коэрция CSV-значения в Java-тип. Поддерживается только {@code true}/{@code false} →
     * Boolean — это однозначная семантика. Числа намеренно НЕ распознаются: иначе
     * enum-валидация типа {@code "stage" ∈ {"1","2","3"}} ломается, потому что
     * {@code "1"} бы стало Long. Для чисел пользователь указывает их через JSON-колонку
     * {@code attributes} (типы там сохраняются ровно как в JSON).
     */
    private static Object coerce(String s) {
        if ("true".equalsIgnoreCase(s)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(s)) return Boolean.FALSE;
        return s;
    }

    private static LocalDate parseDate(String raw, int rowIndex, String field) {
        String s = nullIfBlank(raw);
        if (s == null) return null;
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "Row " + rowIndex + ": " + field + " must be ISO date (YYYY-MM-DD), got '" + s + "'");
        }
    }

    public record Row(
            int rowIndex,
            List<String> keyParts,
            List<String> parentKey,
            String labelRu,
            String labelEn,
            String descriptionRu,
            String descriptionEn,
            Map<String, Object> attributes,
            Integer orderIndex,
            String status,
            LocalDate effectiveFrom,
            LocalDate effectiveTo) {}
}
