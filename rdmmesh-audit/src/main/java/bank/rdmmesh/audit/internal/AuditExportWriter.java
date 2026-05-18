package bank.rdmmesh.audit.internal;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

import bank.rdmmesh.audit.internal.dao.AuditLogDao.ExportRow;

/**
 * Pure-сериализаторы для audit-export endpoint'а (E14 round 4).
 *
 * <p>Два формата:
 * <ul>
 *   <li><b>CSV</b> — RFC 4180. Header + N data-rows. Поля, содержащие двойную
 *       кавычку, запятую или newline, заворачиваются в кавычки; внутренние
 *       кавычки удваиваются ({@code "} → {@code ""}).</li>
 *   <li><b>NDJSON</b> — Newline-Delimited JSON. Одна валидная JSON-row на
 *       строку, разделитель {@code "\n"}. payload включается inline как
 *       вложенный объект (через {@code payload} jsonb-колонку), что упрощает
 *       jq-парсинг выгрузки.</li>
 * </ul>
 *
 * <p>Класс stateless: писатели принимают {@link Writer} и пишут в него.
 * Все методы NOT thread-safe относительно одного Writer'а (по контракту JDK).
 */
public final class AuditExportWriter {

    public static final List<String> CSV_HEADERS = List.of(
            "id",
            "event_id",
            "event_type",
            "aggregate_type",
            "aggregate_id",
            "actor",
            "occurred_at",
            "payload",
            "payload_canonical",
            "prev_hash",
            "entry_hash");

    private AuditExportWriter() {}

    public static void writeCsvHeader(Writer out) throws IOException {
        boolean first = true;
        for (String h : CSV_HEADERS) {
            if (!first) out.write(',');
            out.write(h);
            first = false;
        }
        out.write("\r\n");
    }

    public static void writeCsvRow(Writer out, ExportRow r) throws IOException {
        writeCsvCell(out, Long.toString(r.id())); out.write(',');
        writeCsvCell(out, r.eventId() == null ? "" : r.eventId().toString()); out.write(',');
        writeCsvCell(out, nullToEmpty(r.eventType())); out.write(',');
        writeCsvCell(out, nullToEmpty(r.aggregateType())); out.write(',');
        writeCsvCell(out, r.aggregateId() == null ? "" : r.aggregateId().toString()); out.write(',');
        writeCsvCell(out, r.actor() == null ? "" : r.actor().toString()); out.write(',');
        writeCsvCell(out, r.occurredAt() == null ? "" : r.occurredAt().toString()); out.write(',');
        writeCsvCell(out, nullToEmpty(r.payload())); out.write(',');
        writeCsvCell(out, nullToEmpty(r.payloadCanonical())); out.write(',');
        writeCsvCell(out, nullToEmpty(r.prevHash())); out.write(',');
        writeCsvCell(out, nullToEmpty(r.entryHash()));
        out.write("\r\n");
    }

    /**
     * Записывает одну CSV-ячейку с RFC 4180 escaping. Quoting применяется только
     * если необходимо — для простых ASCII-строк без спецсимволов остаётся как есть,
     * что даёт компактный output и совместимость с naïve parser'ами (Excel и т.п.).
     */
    static void writeCsvCell(Writer out, String value) throws IOException {
        if (value == null || value.isEmpty()) return;
        boolean needsQuoting = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == ',' || c == '"' || c == '\n' || c == '\r') {
                needsQuoting = true;
                break;
            }
        }
        if (!needsQuoting) {
            out.write(value);
            return;
        }
        out.write('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') out.write('"');   // escape удвоением
            out.write(c);
        }
        out.write('"');
    }

    /**
     * NDJSON-row: одна валидная JSON-строка с обязательным trailing newline.
     *
     * <p>{@code payload} прокидывается inline как embedded JSON (а не как
     * escaped-string), потому что в БД он лежит как jsonb. Аналогично для
     * {@code payload_canonical} — там тоже валидный JSON.
     *
     * <p>Записываем вручную через StringBuilder без Jackson — нужен полный
     * контроль над порядком ключей (stable order упрощает diff между
     * экспортами), и не хочется заводить ObjectMapper зависимость только под
     * 11 полей.
     */
    public static void writeNdjsonRow(Writer out, ExportRow r) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');
        appendJsonField(sb, "id", r.id());
        sb.append(',');
        appendJsonField(sb, "event_id", r.eventId());
        sb.append(',');
        appendJsonField(sb, "event_type", r.eventType());
        sb.append(',');
        appendJsonField(sb, "aggregate_type", r.aggregateType());
        sb.append(',');
        appendJsonField(sb, "aggregate_id", r.aggregateId());
        sb.append(',');
        appendJsonField(sb, "actor", r.actor());
        sb.append(',');
        appendJsonField(sb, "occurred_at", r.occurredAt() == null ? null : r.occurredAt().toString());
        sb.append(',');
        // Embedded JSON (не строка). Если payload пустой/null — пишем null.
        sb.append("\"payload\":").append(r.payload() == null || r.payload().isEmpty() ? "null" : r.payload());
        sb.append(',');
        sb.append("\"payload_canonical\":").append(r.payloadCanonical() == null || r.payloadCanonical().isEmpty() ? "null" : r.payloadCanonical());
        sb.append(',');
        appendJsonField(sb, "prev_hash", r.prevHash());
        sb.append(',');
        appendJsonField(sb, "entry_hash", r.entryHash());
        sb.append("}\n");
        out.write(sb.toString());
    }

    private static void appendJsonField(StringBuilder sb, String key, Object value) {
        sb.append('"').append(key).append("\":");
        if (value == null) {
            sb.append("null");
            return;
        }
        if (value instanceof Number n) {
            sb.append(n);
            return;
        }
        // Все прочие — UUID, String, OffsetDateTime.toString() — сериализуем как JSON-строку.
        sb.append('"');
        String s = value.toString();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
