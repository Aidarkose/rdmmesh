package bank.rdmmesh.authoring.internal.xlsx;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;

import bank.rdmmesh.authoring.internal.csv.CsvBulkParser;

/**
 * Парсер pivot-XLSX для матриц миграций рейтингов (E19 Commit 3).
 *
 * <p>Формат входа: «квадратная» матрица, где первая строка — заголовки колонок
 * ({@code to_rating}'s), первая колонка — заголовки строк ({@code from_rating}'s),
 * остальные ячейки — числовые вероятности перехода. Пример (из постановки заказчика):
 *
 * <pre>
 *            AAA     A      BB     B
 *   AAA    0.880   0.112  0.006  0.001
 *   A      0.023   0.865  0.095  0.015
 *   BB     0.001   0.050  0.802  0.127
 *   B      0.000   0.005  0.185  0.720
 * </pre>
 *
 * <p><b>Раскладка в triples.</b> Каждая ячейка {@code (from, to)} → triple
 * {@code key_parts=[from, to, horizon]} с {@code attributes.probability=p}, где
 * {@code horizon} приходит параметром конструктора (а не из XLSX). Делегирует в
 * {@link CsvBulkParser#buildRow} тем же приёмом DRY, что у {@link XlsxBulkParser}.
 *
 * <p><b>Политика residual.</b> Если в строке сумма вероятностей &lt; 1 ± {@code TOLERANCE}:
 * <ul>
 *   <li>{@link RowResidualPolicy#IMPLICIT_DEFAULT} — допишет ячейку
 *       {@code (from, absorbingStateCode, horizon)} с {@code probability = 1 - Σ}.
 *       Имя absorbing-колонки — по умолчанию {@code "D"} (можно переопределить).</li>
 *   <li>{@link RowResidualPolicy#STRICT} — бросит {@link IllegalArgumentException}
 *       с понятным сообщением и индексом строки.</li>
 *   <li>{@link RowResidualPolicy#FREE} — сумма по строке вообще не проверяется
 *       (для матриц, где ячейки — flow-rates, не вероятности; например DPD
 *       delinquency matrix, где Σ строки может быть и 0.003, и 2.4).</li>
 * </ul>
 * IMPLICIT_DEFAULT выбран по умолчанию по согласованию с заказчиком: матрицы
 * публикуются без явной колонки D (residual = PD абсорбирующего состояния).
 *
 * <p><b>Что НЕ делает.</b> Парсер не валидирует absorbing-state consistency,
 * не добавляет row для absorbing-state (D→D=1, D→{others}=0) — это policies
 * следующего уровня (см. E19 handoff §4.2). Парсер только раскладывает pivot
 * в triples и аккуратно дописывает residual-колонку.
 */
public final class MatrixPivotSheetParser {

    private static final double TOLERANCE = 1e-3;
    private static final String DEFAULT_ABSORBING_CODE = "D";

    private final ObjectMapper json;
    private final String horizon;
    private final RowResidualPolicy policy;
    private final String absorbingCode;

    public MatrixPivotSheetParser(ObjectMapper json, String horizon, RowResidualPolicy policy) {
        this(json, horizon, policy, DEFAULT_ABSORBING_CODE);
    }

    public MatrixPivotSheetParser(
            ObjectMapper json, String horizon, RowResidualPolicy policy, String absorbingCode) {
        if (horizon == null || horizon.isBlank()) {
            throw new IllegalArgumentException("horizon is required for pivot layout");
        }
        if (policy == null) {
            throw new IllegalArgumentException("row_residual_policy is required");
        }
        this.json = json;
        this.horizon = horizon.trim();
        this.policy = policy;
        this.absorbingCode = absorbingCode == null || absorbingCode.isBlank()
                ? DEFAULT_ABSORBING_CODE
                : absorbingCode.trim();
    }

    /** Результат разбора: triples + сколько residual-ячеек дописано (для UI-отчёта). */
    public record PivotParseResult(List<CsvBulkParser.Row> rows, int implicitDefaultAdded) {}

    public PivotParseResult parse(InputStream in) throws IOException {
        try (ReadableWorkbook wb = new ReadableWorkbook(in)) {
            List<Sheet> sheets = wb.getSheets().toList();
            if (sheets.isEmpty()) {
                throw new IllegalArgumentException("XLSX-книга не содержит ни одного листа");
            }
            // Берём первый лист, на котором есть валидный pivot-заголовок:
            // (a) первая строка содержит ≥ 2 непустых заголовка (label + ≥1 to_rating),
            // (b) в данных есть хотя бы одна строка с непустой первой ячейкой.
            StringBuilder seen = new StringBuilder();
            for (Sheet sheet : sheets) {
                try (Stream<Row> rows = sheet.openStream()) {
                    Iterator<Row> it = rows.iterator();
                    if (!it.hasNext()) continue;
                    List<String> header = readHeader(it.next());
                    if (header.size() < 2) {
                        appendDiag(seen, sheet.getName(), header);
                        continue;
                    }
                    // header[0] — label первой колонки (имя оси from); может быть пустым
                    // в пользовательских книгах. header[1..n] — to_rating'и.
                    List<String> toRatings = header.subList(1, header.size());
                    return readData(toRatings, it);
                }
            }
            throw new IllegalArgumentException(
                    "Ни на одном листе нет валидного pivot-заголовка (минимум 2 колонки:"
                            + " первая — label оси, далее — to_rating'и)."
                            + " Найдено по листам — " + seen);
        }
    }

    private static void appendDiag(StringBuilder seen, String name, List<String> header) {
        if (seen.length() > 0) seen.append("; ");
        seen.append('\'').append(name).append("': ");
        if (header.isEmpty()) seen.append("(пустой заголовок)");
        else seen.append(String.join(", ", header));
    }

    private PivotParseResult readData(List<String> toRatings, Iterator<Row> it) {
        List<CsvBulkParser.Row> out = new ArrayList<>();
        int implicitAdded = 0;
        int rowIdxOut = 0;
        int dataRowIdx = 0;
        while (it.hasNext()) {
            Row row = it.next();
            // Первая ячейка = from_rating; пустая → пропускаем (filler row).
            String from = trimToNull(row.getCellText(0));
            if (from == null) {
                dataRowIdx++;
                continue;
            }
            double sum = 0.0;
            // Собираем непустые ячейки строки в (from, to, horizon, p) triples.
            for (int c = 0; c < toRatings.size(); c++) {
                String to = toRatings.get(c);
                if (to == null || to.isBlank()) continue;
                String cellText = trimToNull(row.getCellText(c + 1));
                if (cellText == null) continue;
                double p = parseProbability(cellText, dataRowIdx, from, to);
                sum += p;
                out.add(buildTriple(from, to, p, rowIdxOut++));
            }
            // Политика residual. FREE — сумма не проверяется (flow-rates вместо вероятностей).
            if (policy != RowResidualPolicy.FREE) {
                double residual = 1.0 - sum;
                if (Math.abs(residual) > TOLERANCE) {
                    if (policy == RowResidualPolicy.IMPLICIT_DEFAULT && residual > 0) {
                        out.add(buildTriple(from, absorbingCode, residual, rowIdxOut++));
                        implicitAdded++;
                    } else {
                        throw new IllegalArgumentException(
                                "Row " + dataRowIdx + " (from='" + from + "'): сумма вероятностей "
                                        + format(sum) + " не стохастична (ожидалось 1 ± " + TOLERANCE + ")."
                                        + (policy == RowResidualPolicy.STRICT
                                                ? " Включён STRICT — повторите импорт с"
                                                        + " row_residual_policy=implicit_default"
                                                        + " (residual в " + absorbingCode + ") или"
                                                        + " row_residual_policy=free (без проверки суммы,"
                                                        + " для delinquency/flow-matrix)."
                                                : " IMPLICIT_DEFAULT применим только когда Σ < 1"
                                                        + " (residual > 0), а здесь сумма уже превышает 1."
                                                        + " Используйте row_residual_policy=free, если"
                                                        + " ячейки — flow-rates, а не вероятности."));
                    }
                }
            }
            dataRowIdx++;
        }
        return new PivotParseResult(out, implicitAdded);
    }

    private CsvBulkParser.Row buildTriple(String from, String to, double p, int outIdx) {
        Map<String, String> raw = new LinkedHashMap<>();
        // key_parts как JSON-array: тот же контракт, что у CSV (см. CsvBulkParser.buildRow).
        try {
            raw.put("key_parts", json.writeValueAsString(List.of(from, to, horizon)));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // List.of(String,String,String) — Jackson не должен падать на этом.
            throw new IllegalStateException("Cannot serialize key_parts: " + e.getMessage(), e);
        }
        // attributes как inline-JSON; число сохраняется как Double, чтобы тип
        // дошёл до JSONB без коэрции в string (E19 — probability обязан остаться number).
        raw.put("attributes", "{\"probability\":" + format(p) + "}");
        return CsvBulkParser.buildRow(json, raw, outIdx);
    }

    /** Заголовок pivot-листа → массив непустых имён колонок (с обрезкой пустого хвоста). */
    private static List<String> readHeader(Row row) {
        List<String> header = new ArrayList<>();
        int cells = row.getCellCount();
        for (int c = 0; c < cells; c++) {
            String t = row.getCellText(c);
            header.add(normalizeCode(t == null ? "" : t.trim()));
        }
        while (!header.isEmpty() && header.get(header.size() - 1).isEmpty()) {
            header.remove(header.size() - 1);
        }
        return header;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : normalizeCode(t);
    }

    /**
     * Excel хранит целые числа как double 1.0; fastexcel-reader возвращает '1.0'.
     * Для cross-codeset lookup (E20) и человеческой читаемости нормализуем
     * '1.0' / '10.00' → '1' / '10'. Не трогаем '1.5', 'AAA', '1Y', '-3.0' (но -3.0 → -3).
     */
    private static String normalizeCode(String s) {
        if (s == null || s.isEmpty()) return s;
        if (s.matches("-?\\d+\\.0+")) {
            return s.substring(0, s.indexOf('.'));
        }
        return s;
    }

    private static double parseProbability(String cell, int rowIdx, String from, String to) {
        try {
            double v = Double.parseDouble(cell.replace(',', '.'));
            if (v < 0 || v > 1) {
                throw new IllegalArgumentException(
                        "Row " + rowIdx + " cell (" + from + " → " + to + "): probability "
                                + v + " вне [0, 1]");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Row " + rowIdx + " cell (" + from + " → " + to + "): не число '" + cell + "'");
        }
    }

    /** 6 знаков после точки — баланс между читаемостью и аккуратностью округления residual. */
    private static String format(double p) {
        return String.format(java.util.Locale.ROOT, "%.6f", p);
    }

    /** Дедуп для тестов / future use (не использовано, но оставлено для интроспекции). */
    @SuppressWarnings("unused")
    private static Map<String, Integer> indexByName(List<String> names) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < names.size(); i++) m.put(names.get(i), i);
        return m;
    }

    public enum RowResidualPolicy {
        IMPLICIT_DEFAULT,
        STRICT,
        FREE;

        public static RowResidualPolicy parseOrDefault(String s) {
            if (s == null || s.isBlank()) return IMPLICIT_DEFAULT;
            String norm = s.trim().toUpperCase(java.util.Locale.ROOT);
            return switch (norm) {
                case "IMPLICIT_DEFAULT", "IMPLICIT-DEFAULT", "IMPLICIT" -> IMPLICIT_DEFAULT;
                case "STRICT" -> STRICT;
                case "FREE", "FREE_FORM", "FREE-FORM", "NONE", "NO_CHECK" -> FREE;
                default -> throw new IllegalArgumentException(
                        "Unknown row_residual_policy='" + s
                                + "'; allowed: implicit_default | strict | free");
            };
        }
    }
}
