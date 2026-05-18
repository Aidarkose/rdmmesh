package bank.rdmmesh.authoring.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jdbi.v3.core.Jdbi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import bank.rdmmesh.api.port.PublishedSnapshotPort;
import bank.rdmmesh.authoring.internal.dao.CodeItemDao;
import bank.rdmmesh.authoring.internal.dao.CodeItemDao.ItemRow;

/**
 * Реализация {@link PublishedSnapshotPort}.
 *
 * <h3>Алгоритм канонизации (фиксирован на E6 — менять = ломать verify прежних версий):</h3>
 * <ol>
 *   <li>{@code findAll(versionId)} — items в порядке БД.</li>
 *   <li>Для каждого item: парсим {@code key_parts}/{@code parent_key}/{@code parent_ref}/{@code attributes}
 *       через Jackson (любой порядок ключей в JSONB унифицируется).</li>
 *   <li>Складываем поля item в {@link LinkedHashMap} в фиксированном порядке (см. {@code KEYS});
 *       JSON-объекты внутри (parsed JSONB) идут как {@link TreeMap} — Jackson с
 *       {@code SORT_PROPERTIES_ALPHABETICALLY} печатает их с отсортированными ключами.</li>
 *   <li>Сортируем массив items по компактной JSON-форме их {@code key_parts}.</li>
 *   <li>Оборачиваем в {@code {"version_id": "...", "items": [...]}} и сериализуем без whitespace.</li>
 * </ol>
 */
public final class PublishedSnapshotAdapter implements PublishedSnapshotPort {

    private static final ObjectMapper PARSER = new ObjectMapper();

    /**
     * Jackson сортирует Map-entries по ключам (мы строим items как LinkedHashMap, поэтому
     * нужен только этот feature — POJO sorting нам не нужен, у нас всё через Map'ы).
     */
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

    private final Jdbi jdbi;

    public PublishedSnapshotAdapter(Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    @Override
    public byte[] canonicalSnapshotBytes(UUID versionId) {
        List<ItemRow> rows = jdbi.withExtension(CodeItemDao.class, dao -> dao.findAll(versionId));

        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (ItemRow r : rows) {
            items.add(buildItem(r));
        }
        items.sort(Comparator.comparing(PublishedSnapshotAdapter::keyPartsCompact));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version_id", versionId.toString());
        root.put("items", items);
        try {
            return CANONICAL.writeValueAsBytes(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("canonical snapshot serialisation failed: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildItem(ItemRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key_parts", parseJson(r.keyPartsJson()));
        m.put("parent_key", parseJson(r.parentKeyJson()));
        m.put("parent_ref", parseJson(r.parentRefJson()));
        m.put("label_ru", r.labelRu());
        m.put("label_en", r.labelEn());
        m.put("description_ru", r.descriptionRu());
        m.put("description_en", r.descriptionEn());
        m.put("attributes", parseJson(r.attributesJson()));
        m.put("order_index", r.orderIndex());
        m.put("status", r.status());
        m.put("effective_from", r.effectiveFrom() == null ? null : r.effectiveFrom().toString());
        m.put("effective_to", r.effectiveTo() == null ? null : r.effectiveTo().toString());
        // На всякий случай: если в ItemRow появятся новые поля, KEYS даёт явный shape.
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (String k : KEYS) {
            ordered.put(k, m.get(k));
        }
        return ordered;
    }

    private static Object parseJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return PARSER.readValue(json, Object.class);
        } catch (IOException e) {
            throw new IllegalStateException("invalid JSON in stored item: " + e.getMessage(), e);
        }
    }

    private static String keyPartsCompact(Map<String, Object> item) {
        try {
            // Ключи отсортированы лексикографически — но key_parts массив, и сортировка ключей внутри
            // не влияет; для массива Jackson сохраняет порядок элементов как есть.
            return CANONICAL.writeValueAsString(item.get("key_parts"));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to compact key_parts: " + e.getMessage(), e);
        }
    }
}
