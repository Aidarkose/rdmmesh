package bank.rdmmesh.authoring.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * Общий канонизатор snapshot'а версии — единственный источник алгоритма {@code content_hash}
 * (E6). Используется и {@link PublishedSnapshotAdapter} (из {@code code_item}), и relational
 * store'ом (из {@code rd_data}), поэтому байтовая форма совпадает по построению: если поля
 * item'ов равны — равны и canonical bytes, а значит и {@code content_hash}.
 *
 * <h3>Алгоритм (фиксирован на E6 — менять = ломать verify прежних версий):</h3>
 * <ol>
 *   <li>Поля каждого item складываются в {@link LinkedHashMap} в фиксированном порядке {@link #KEYS};
 *       вложенные JSON-объекты (parsed JSONB) Jackson печатает с отсортированными ключами
 *       ({@code ORDER_MAP_ENTRIES_BY_KEYS}).</li>
 *   <li>Массив items сортируется по компактной JSON-форме {@code key_parts}.</li>
 *   <li>Обёртка {@code {"version_id":"…","items":[…]}} сериализуется без whitespace.</li>
 * </ol>
 */
public final class CanonicalSnapshot {

    private static final ObjectMapper PARSER = new ObjectMapper();

    private static final ObjectMapper CANONICAL = JsonMapper.builder()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .build();

    /** Поля item в каноническом порядке (важно: только эти поля попадают в hash). */
    private static final String[] KEYS = {
            "key_parts", "parent_key", "parent_ref",
            "label_ru", "label_en", "description_ru", "description_en",
            "attributes", "order_index", "status",
            "effective_from", "effective_to"
    };

    private CanonicalSnapshot() {}

    /**
     * Один item в каноническом порядке полей. Значения уже распарсены в Java-объекты
     * ({@code keyParts}/{@code parentKey} — List, {@code parentRef}/{@code attributes} — Map,
     * либо null); даты — строки ISO.
     */
    public static Map<String, Object> item(
            Object keyParts,
            Object parentKey,
            Object parentRef,
            String labelRu,
            String labelEn,
            String descriptionRu,
            String descriptionEn,
            Object attributes,
            Integer orderIndex,
            String status,
            String effectiveFrom,
            String effectiveTo) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("key_parts", keyParts);
        values.put("parent_key", parentKey);
        values.put("parent_ref", parentRef);
        values.put("label_ru", labelRu);
        values.put("label_en", labelEn);
        values.put("description_ru", descriptionRu);
        values.put("description_en", descriptionEn);
        values.put("attributes", attributes);
        values.put("order_index", orderIndex);
        values.put("status", status);
        values.put("effective_from", effectiveFrom);
        values.put("effective_to", effectiveTo);
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String k : KEYS) {
            ordered.put(k, values.get(k));
        }
        return ordered;
    }

    /** Detерминированные bytes снапшота: сортировка items по компактному key_parts + обёртка. */
    public static byte[] bytes(String versionId, List<Map<String, Object>> items) {
        List<Map<String, Object>> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparing(CanonicalSnapshot::keyPartsCompact));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version_id", versionId);
        root.put("items", sorted);
        try {
            return CANONICAL.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("canonical snapshot serialisation failed: " + e.getMessage(), e);
        }
    }

    /** Хэш канонического снапшота: {@code sha256_hex(bytes(versionId, items))}. */
    public static String contentHash(String versionId, List<Map<String, Object>> items) {
        return sha256Hex(bytes(versionId, items));
    }

    /** Парсит JSON-текст (jsonb-колонка/блоб) в Java-объект; null/blank → null. */
    public static Object parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return PARSER.readValue(json, Object.class);
        } catch (IOException e) {
            throw new IllegalStateException("invalid JSON in stored item: " + e.getMessage(), e);
        }
    }

    public static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String keyPartsCompact(Map<String, Object> item) {
        try {
            return CANONICAL.writeValueAsString(item.get("key_parts"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to compact key_parts: " + e.getMessage(), e);
        }
    }
}
