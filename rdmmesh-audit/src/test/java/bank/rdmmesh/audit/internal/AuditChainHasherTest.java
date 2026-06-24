package bank.rdmmesh.audit.internal;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

final class AuditChainHasherTest {

    private final AuditChainHasher hasher = new AuditChainHasher(AuditChainHasher.defaultMapper());

    @Test
    void canonical_payload_sorts_keys_alphabetically() {
        Map<String, Object> orig = new LinkedHashMap<>();
        orig.put("z", 1);
        orig.put("a", "x");
        orig.put("m", true);

        String canonical = hasher.canonicalPayload(orig);

        // Sorted keys: a → m → z. Jackson по умолчанию не вставляет whitespace.
        assertThat(canonical).isEqualTo("{\"a\":\"x\",\"m\":true,\"z\":1}");
    }

    @Test
    void canonical_payload_nested_objects_sorted_recursively() {
        Map<String, Object> orig = new LinkedHashMap<>();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("b", 2);
        nested.put("a", 1);
        orig.put("y", nested);
        orig.put("x", 0);

        String canonical = hasher.canonicalPayload(orig);

        assertThat(canonical).isEqualTo("{\"x\":0,\"y\":{\"a\":1,\"b\":2}}");
    }

    @Test
    void canonical_payload_drops_null_fields_by_default() {
        Map<String, Object> orig = new LinkedHashMap<>();
        orig.put("a", "v");
        orig.put("b", null);

        String canonical = hasher.canonicalPayload(orig);

        // NON_NULL inclusion отрезает null-поля.
        assertThat(canonical).isEqualTo("{\"a\":\"v\"}");
    }

    @Test
    void canonical_payload_null_value_serialises_to_string_null() {
        // Java null → JSON null literal (через Jackson writeValueAsString(null)).
        String canonical = hasher.canonicalPayload(null);
        assertThat(canonical).isEqualTo("null");
    }

    @Test
    void compute_entry_hash_is_deterministic() {
        UUID eventId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        OffsetDateTime ts = OffsetDateTime.of(
                2026, 5, 12, 10, 30, 45, 123_456_000, ZoneOffset.UTC);

        String first = hasher.computeEntryHash(null, eventId, "WORKFLOW_TRANSITION",
                "{\"x\":1}", ts);
        String second = hasher.computeEntryHash(null, eventId, "WORKFLOW_TRANSITION",
                "{\"x\":1}", ts);

        assertThat(first).isEqualTo(second);
        assertThat(first).hasSize(64).matches("[a-f0-9]{64}");
    }

    @Test
    void compute_entry_hash_changes_when_prev_changes() {
        UUID eventId = UUID.randomUUID();
        OffsetDateTime ts = OffsetDateTime.now(ZoneOffset.UTC);

        String withoutPrev = hasher.computeEntryHash(null, eventId, "X", "{}", ts);
        String withPrev    = hasher.computeEntryHash("a".repeat(64), eventId, "X", "{}", ts);

        assertThat(withoutPrev).isNotEqualTo(withPrev);
    }

    @Test
    void compute_entry_hash_changes_when_payload_changes() {
        UUID eventId = UUID.randomUUID();
        OffsetDateTime ts = OffsetDateTime.now(ZoneOffset.UTC);

        String h1 = hasher.computeEntryHash(null, eventId, "X", "{\"a\":1}", ts);
        String h2 = hasher.computeEntryHash(null, eventId, "X", "{\"a\":2}", ts);

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void compute_entry_hash_changes_when_event_type_changes() {
        UUID eventId = UUID.randomUUID();
        OffsetDateTime ts = OffsetDateTime.now(ZoneOffset.UTC);

        String h1 = hasher.computeEntryHash(null, eventId, "A", "{}", ts);
        String h2 = hasher.computeEntryHash(null, eventId, "B", "{}", ts);

        assertThat(h1).isNotEqualTo(h2);
    }

    @Test
    void compute_entry_hash_matches_external_sha256_of_canonical_input() throws Exception {
        // Этот тест фиксирует точный формат canonical_input. Если кто-то решит
        // поменять разделитель/порядок полей — тест сломается. Это намеренно:
        // алгоритм должен совпадать с SQL-формулой backfill'а V072 1-в-1.
        UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        OffsetDateTime ts = OffsetDateTime.of(
                2026, 5, 12, 9, 0, 0, 0, ZoneOffset.UTC);
        String prev = "deadbeef".repeat(8);            // 64-char hex
        String payload = "{\"answer\":42}";
        String eventType = "WORKFLOW_TRANSITION";

        String canonInput = prev
                + "|" + eventId
                + "|" + eventType
                + "|" + payload
                + "|2026-05-12T09:00:00.000000Z";
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonInput.getBytes(UTF_8));
        StringBuilder expected = new StringBuilder(64);
        for (byte b : digest) {
            expected.append(Character.forDigit((b >> 4) & 0xF, 16));
            expected.append(Character.forDigit(b & 0xF, 16));
        }

        String actual = hasher.computeEntryHash(prev, eventId, eventType, payload, ts);

        assertThat(actual).isEqualTo(expected.toString());
    }

    @Test
    void format_occurred_at_truncates_to_microseconds_in_utc() {
        // 123_456_789 nanos → 123_456 micros (drop последние 3 nanos).
        OffsetDateTime t = OffsetDateTime.of(
                2026, 1, 31, 23, 59, 59, 123_456_789, ZoneOffset.ofHours(3));

        String formatted = AuditChainHasher.formatOccurredAt(t);

        // 23:59:59 в +03 → 20:59:59 в UTC. Микросекунды: 123_456.
        assertThat(formatted).isEqualTo("2026-01-31T20:59:59.123456Z");
    }

    @Test
    void format_occurred_at_zero_micros_pads_to_six_digits() {
        OffsetDateTime t = OffsetDateTime.of(
                2026, 5, 12, 10, 0, 0, 0, ZoneOffset.UTC);

        assertThat(AuditChainHasher.formatOccurredAt(t))
                .isEqualTo("2026-05-12T10:00:00.000000Z");
    }

    @Test
    void default_mapper_serialises_offset_date_time_to_iso_string_not_number() {
        // Регрессия: убедимся, что defaultMapper() не пишет timestamps как
        // numeric epoch — chain должна быть human-readable.
        ObjectMapper m = AuditChainHasher.defaultMapper();
        OffsetDateTime t = OffsetDateTime.of(
                2026, 5, 12, 10, 0, 0, 0, ZoneOffset.UTC);

        Map<String, Object> wrapper = new LinkedHashMap<>();
        wrapper.put("when", t);

        AuditChainHasher localHasher = new AuditChainHasher(m);
        String canon = localHasher.canonicalPayload(wrapper);

        assertThat(canon).contains("2026-05-12");
        assertThat(canon).doesNotContain("\"when\":1");  // не numeric epoch
    }
}
