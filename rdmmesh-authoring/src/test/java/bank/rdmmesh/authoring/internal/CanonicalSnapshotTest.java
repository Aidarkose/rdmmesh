package bank.rdmmesh.authoring.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Pure-тесты общего канонизатора {@link CanonicalSnapshot}. Гарантируют детерминизм
 * (порядок items/ключей атрибутов/типы чисел не влияют на байты) и байтовую совместимость
 * code_item-формы с relational-формой одного и того же логического item'а — основа того,
 * что {@code content_hash} из {@code rd_data} совпадёт с хэшем из {@code code_item}.
 */
class CanonicalSnapshotTest {

    private static Map<String, Object> sampleItem(List<String> key, Map<String, Object> attrs) {
        return CanonicalSnapshot.item(
                key, null, null, "Отделение", "Branch", null, null,
                attrs, 1, "ACTIVE", "2026-01-01", null);
    }

    @Test
    void item_order_in_list_does_not_change_bytes() {
        Map<String, Object> a = sampleItem(List.of("001"), Map.of("pd", 0.5));
        Map<String, Object> b = sampleItem(List.of("002"), Map.of("pd", 0.7));
        assertThat(CanonicalSnapshot.bytes("v", List.of(a, b)))
                .isEqualTo(CanonicalSnapshot.bytes("v", List.of(b, a)));
    }

    @Test
    void attribute_key_order_does_not_change_bytes() {
        Map<String, Object> m1 = new LinkedHashMap<>();
        m1.put("b", 1);
        m1.put("a", 2);
        Map<String, Object> m2 = new LinkedHashMap<>();
        m2.put("a", 2);
        m2.put("b", 1);
        assertThat(CanonicalSnapshot.bytes("v", List.of(sampleItem(List.of("1"), m1))))
                .isEqualTo(CanonicalSnapshot.bytes("v", List.of(sampleItem(List.of("1"), m2))));
    }

    @Test
    void integer_and_long_attributes_serialise_identically() {
        Map<String, Object> asInt = Map.of("n", Integer.valueOf(42));
        Map<String, Object> asLong = Map.of("n", Long.valueOf(42));
        assertThat(CanonicalSnapshot.bytes("v", List.of(sampleItem(List.of("1"), asInt))))
                .isEqualTo(CanonicalSnapshot.bytes("v", List.of(sampleItem(List.of("1"), asLong))));
    }

    @Test
    void content_hash_is_sha256_hex_and_stable() {
        Map<String, Object> item = sampleItem(List.of("001"), Map.of("pd", 0.5));
        String h1 = CanonicalSnapshot.contentHash("v", List.of(item));
        String h2 = CanonicalSnapshot.contentHash("v", List.of(item));
        assertThat(h1).hasSize(64).matches("[0-9a-f]{64}").isEqualTo(h2);
    }

    @Test
    void canonical_bytes_sort_keys_alphabetically() {
        // ORDER_MAP_ENTRIES_BY_KEYS: ключи печатаются в алфавитном порядке (детерминизм
        // не зависит от порядка вставки) — attributes < key_parts < status.
        String s = new String(
                CanonicalSnapshot.bytes("v", List.of(sampleItem(List.of("1"), Map.of()))),
                StandardCharsets.UTF_8);
        assertThat(s.indexOf("attributes")).isLessThan(s.indexOf("key_parts"));
        assertThat(s.indexOf("key_parts")).isLessThan(s.indexOf("status"));
        assertThat(s).contains("\"version_id\":\"v\"");
    }

    @Test
    void parity_code_item_form_equals_relational_form() {
        // code_item: key_parts/attributes распарсены из jsonb-блобов
        Object codeKey = CanonicalSnapshot.parseJson("[\"001\",\"42\"]");
        Object codeAttrs = CanonicalSnapshot.parseJson("{\"pd\":0.5}");
        Map<String, Object> codeItem = CanonicalSnapshot.item(
                codeKey, null, null, "Отделение", null, null, null,
                codeAttrs, 3, "ACTIVE", "2026-01-01", null);

        // relational: ключи стрингованы из типизированных колонок, attrs собраны из колонок
        List<String> relKey = List.of("001", "42");
        Map<String, Object> relAttrs = new LinkedHashMap<>();
        relAttrs.put("pd", 0.5);
        Map<String, Object> relItem = CanonicalSnapshot.item(
                relKey, null, null, "Отделение", null, null, null,
                relAttrs, 3, "ACTIVE", "2026-01-01", null);

        assertThat(CanonicalSnapshot.bytes("v", List.of(codeItem)))
                .isEqualTo(CanonicalSnapshot.bytes("v", List.of(relItem)));
    }
}
