package bank.rdmmesh.audit.internal;

import java.io.StringWriter;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import bank.rdmmesh.audit.internal.dao.AuditLogDao.ExportRow;

import static org.assertj.core.api.Assertions.assertThat;

final class AuditExportWriterTest {

    private static final UUID U_EVT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID U_AGG = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID U_ACT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final OffsetDateTime TS =
            OffsetDateTime.of(2026, 5, 12, 10, 30, 45, 123_456_000, ZoneOffset.UTC);

    // ─── CSV ──────────────────────────────────────────────────────────────────────

    @Test
    void csv_header_matches_spec_columns() throws Exception {
        StringWriter sw = new StringWriter();
        AuditExportWriter.writeCsvHeader(sw);

        assertThat(sw.toString())
                .isEqualTo("id,event_id,event_type,aggregate_type,aggregate_id,actor,"
                        + "occurred_at,payload,payload_canonical,prev_hash,entry_hash\r\n");
    }

    @Test
    void csv_row_basic_no_escaping_needed() throws Exception {
        ExportRow r = new ExportRow(
                1L, U_EVT, "WORKFLOW_TRANSITION", "VERSION", U_AGG, U_ACT, TS,
                "{\"to\":\"IN_REVIEW\"}",                         // payload (но содержит quotes!)
                "{\"to\":\"IN_REVIEW\"}",                         // payload_canonical
                null,                                             // prev_hash (первая запись)
                "abc123def456");
        StringWriter sw = new StringWriter();
        AuditExportWriter.writeCsvRow(sw, r);

        String row = sw.toString();
        // ID, event_type, аггрегат, актор, timestamp — без escape'а.
        assertThat(row).startsWith("1,11111111-1111-1111-1111-111111111111,WORKFLOW_TRANSITION,VERSION,");
        // payload содержит quotes → весь cell обёрнут в кавычки + внутренние "" удваиваются.
        assertThat(row).contains(",\"{\"\"to\"\":\"\"IN_REVIEW\"\"}\",");
        // prev_hash null → пустая cell.
        assertThat(row).contains(",,abc123def456");
        assertThat(row).endsWith("\r\n");
    }

    @Test
    void csv_cell_with_comma_is_quoted() throws Exception {
        StringWriter sw = new StringWriter();
        AuditExportWriter.writeCsvCell(sw, "Hello, world");
        assertThat(sw.toString()).isEqualTo("\"Hello, world\"");
    }

    @Test
    void csv_cell_with_double_quote_doubles_it() throws Exception {
        StringWriter sw = new StringWriter();
        AuditExportWriter.writeCsvCell(sw, "She said \"hi\"");
        assertThat(sw.toString()).isEqualTo("\"She said \"\"hi\"\"\"");
    }

    @Test
    void csv_cell_with_newline_is_quoted() throws Exception {
        StringWriter sw = new StringWriter();
        AuditExportWriter.writeCsvCell(sw, "line1\nline2");
        assertThat(sw.toString()).isEqualTo("\"line1\nline2\"");
    }

    @Test
    void csv_cell_empty_or_null_emits_nothing() throws Exception {
        StringWriter sw = new StringWriter();
        AuditExportWriter.writeCsvCell(sw, null);
        AuditExportWriter.writeCsvCell(sw, "");
        assertThat(sw.toString()).isEmpty();
    }

    @Test
    void csv_cell_simple_ascii_unchanged() throws Exception {
        StringWriter sw = new StringWriter();
        AuditExportWriter.writeCsvCell(sw, "VERSION_PUBLISHED");
        assertThat(sw.toString()).isEqualTo("VERSION_PUBLISHED");
    }

    @Test
    void csv_row_null_aggregate_actor_emits_empty_cells() throws Exception {
        ExportRow r = new ExportRow(
                42L, U_EVT, "OWNERSHIP_CHANGED", null, null, null, TS,
                "{}", "{}", "prevhex", "currhex");
        StringWriter sw = new StringWriter();
        AuditExportWriter.writeCsvRow(sw, r);

        String row = sw.toString();
        // 42,evt,OWNERSHIP_CHANGED,,,,2026-...,{},{},prevhex,currhex\r\n
        assertThat(row).startsWith("42,11111111-1111-1111-1111-111111111111,OWNERSHIP_CHANGED,,,,");
        assertThat(row).endsWith(",{},{},prevhex,currhex\r\n");
    }

    // ─── NDJSON ───────────────────────────────────────────────────────────────────

    @Test
    void ndjson_row_emits_one_line_with_trailing_newline() throws Exception {
        ExportRow r = new ExportRow(
                1L, U_EVT, "WORKFLOW_TRANSITION", "VERSION", U_AGG, U_ACT, TS,
                "{\"to\":\"IN_REVIEW\"}", "{\"to\":\"IN_REVIEW\"}",
                null, "abc123");
        StringWriter sw = new StringWriter();
        AuditExportWriter.writeNdjsonRow(sw, r);

        String row = sw.toString();
        assertThat(row).endsWith("\n");
        // одна строка
        assertThat(row.split("\n", -1)).hasSize(2);  // text + empty after trailing \n
        // обязательные поля
        assertThat(row).contains("\"id\":1");
        assertThat(row).contains("\"event_type\":\"WORKFLOW_TRANSITION\"");
        assertThat(row).contains("\"aggregate_type\":\"VERSION\"");
        assertThat(row).contains("\"event_id\":\"11111111-1111-1111-1111-111111111111\"");
    }

    @Test
    void ndjson_payload_is_embedded_object_not_string() throws Exception {
        // payload — это валидный JSON object из jsonb-колонки. NDJSON должен
        // его inline-нуть, а не escape'нуть в строку.
        ExportRow r = new ExportRow(
                1L, U_EVT, "X", null, null, null, TS,
                "{\"action\":\"submit\"}", "{\"action\":\"submit\"}",
                null, "h");
        StringWriter sw = new StringWriter();
        AuditExportWriter.writeNdjsonRow(sw, r);

        // Должно быть `"payload":{"action":"submit"}`, не
        // `"payload":"{\"action\":\"submit\"}"`.
        assertThat(sw.toString()).contains("\"payload\":{\"action\":\"submit\"}");
        assertThat(sw.toString()).contains("\"payload_canonical\":{\"action\":\"submit\"}");
    }

    @Test
    void ndjson_null_payload_emits_json_null() throws Exception {
        ExportRow r = new ExportRow(
                1L, U_EVT, "X", null, null, null, TS,
                null, null, null, "h");
        StringWriter sw = new StringWriter();
        AuditExportWriter.writeNdjsonRow(sw, r);

        assertThat(sw.toString()).contains("\"payload\":null");
        assertThat(sw.toString()).contains("\"payload_canonical\":null");
    }

    @Test
    void ndjson_null_optional_fields_emit_json_null() throws Exception {
        ExportRow r = new ExportRow(
                1L, U_EVT, "OWNERSHIP_CHANGED", null, null, null, TS,
                "{}", "{}", null, "h");
        StringWriter sw = new StringWriter();
        AuditExportWriter.writeNdjsonRow(sw, r);

        assertThat(sw.toString()).contains("\"aggregate_type\":null");
        assertThat(sw.toString()).contains("\"aggregate_id\":null");
        assertThat(sw.toString()).contains("\"actor\":null");
        assertThat(sw.toString()).contains("\"prev_hash\":null");
    }

    @Test
    void ndjson_string_with_special_chars_is_escaped() throws Exception {
        ExportRow r = new ExportRow(
                1L, U_EVT, "X\nY\"Z", null, null, null, TS,
                "{}", "{}", null, "h");
        StringWriter sw = new StringWriter();
        AuditExportWriter.writeNdjsonRow(sw, r);

        // event_type содержит \n и \" — должны быть escaped по JSON-правилам.
        assertThat(sw.toString()).contains("\"event_type\":\"X\\nY\\\"Z\"");
    }
}
