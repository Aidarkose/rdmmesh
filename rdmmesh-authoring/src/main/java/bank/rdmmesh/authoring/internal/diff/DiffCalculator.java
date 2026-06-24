package bank.rdmmesh.authoring.internal.diff;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import bank.rdmmesh.authoring.internal.dao.CodeItemDiffDao.DiffRow;

/**
 * Преобразует «сырые» rows из SQL-функции {@code authoring.code_item_diff_base} в
 * структуру {@link Result} — список entries с полем {@code changed_fields}, которое
 * вычисляется построковым обходом JSON before/after.
 *
 * <p>Стратегия операции:
 * <ul>
 *   <li>{@code ADDED}/{@code REMOVED} — берём как есть из SQL.
 *   <li>{@code CHANGED} с изменением только {@code parent_key}/{@code parent_ref} →
 *       помечаем как {@code MOVED} (SPEC §3.5 diff op'ы).
 *   <li>Остальные {@code CHANGED} — оставляем CHANGED.
 * </ul>
 *
 * <p>{@code changed_fields} — JSON-paths уровня одного шага (например
 * {@code "label_ru"}, {@code "attributes.pd"}, {@code "parent_key"}).
 */
public final class DiffCalculator {

    private static final Set<String> HIERARCHY_FIELDS = Set.of("parent_key", "parent_ref");

    private final ObjectMapper json;

    public DiffCalculator(ObjectMapper json) {
        this.json = json;
    }

    public Result compute(String fromVersion, String toVersion, List<DiffRow> rows) {
        List<Entry> entries = new ArrayList<>(rows.size());
        int added = 0, changed = 0, removed = 0, moved = 0;
        for (DiffRow row : rows) {
            try {
                List<String> keyParts = parseKey(row.keyPartsJson());
                JsonNode before = parseDoc(row.beforeDocJson());
                JsonNode after = parseDoc(row.afterDocJson());
                List<String> fields = changedFields(before, after);
                String op = row.op();
                if ("CHANGED".equals(op) && !fields.isEmpty() && fields.stream().allMatch(HIERARCHY_FIELDS::contains)) {
                    op = "MOVED";
                }
                switch (op) {
                    case "ADDED"   -> added++;
                    case "REMOVED" -> removed++;
                    case "MOVED"   -> moved++;
                    case "CHANGED" -> changed++;
                    default -> { /* UNCHANGED отфильтрован SQL'ом, но оставим throw для надёжности */
                        throw new IllegalStateException("Unexpected op: " + op);
                    }
                }
                entries.add(new Entry(op, keyParts, fields, before, after));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Bad diff row JSON: " + e.getMessage(), e);
            }
        }
        return new Result(fromVersion, toVersion, entries, new Summary(added, changed, removed, moved));
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private List<String> parseKey(String text) throws JsonProcessingException {
        if (text == null || text.isBlank()) return List.of();
        return json.readValue(text, new TypeReference<List<String>>() {});
    }

    private JsonNode parseDoc(String text) throws JsonProcessingException {
        if (text == null || text.isBlank()) return null;
        return json.readTree(text);
    }

    /**
     * Поверхностный обход одного уровня + рекурсия в JSONB-объекты для атрибутов.
     * Возвращает плоский список изменённых fields (с dot-нотацией для вложенных
     * объектов в {@code attributes}). Списки/массивы сравниваются как целое — без
     * элементного diff'а: для бизнес-данных RDM это достаточная гранулярность
     * (мы не сравниваем «двух соседей в списке»).
     */
    private List<String> changedFields(JsonNode a, JsonNode b) {
        if (a == null && b == null) return List.of();
        // ADDED / REMOVED — diff целиком, поле "*" символизирует «всё».
        if (a == null || b == null) return List.of("*");
        Set<String> out = new LinkedHashSet<>();
        Set<String> keys = new LinkedHashSet<>();
        a.fieldNames().forEachRemaining(keys::add);
        b.fieldNames().forEachRemaining(keys::add);
        for (String k : keys) {
            JsonNode av = a.get(k);
            JsonNode bv = b.get(k);
            if (eqJson(av, bv)) continue;
            if (av != null && bv != null && av.isObject() && bv.isObject()) {
                // Рекурсивно — но только один уровень: outer field.subfield.
                Iterator<String> sub = unionFields(av, bv);
                boolean pushedAny = false;
                while (sub.hasNext()) {
                    String s = sub.next();
                    if (!eqJson(av.get(s), bv.get(s))) {
                        out.add(k + "." + s);
                        pushedAny = true;
                    }
                }
                if (!pushedAny) out.add(k);
            } else {
                out.add(k);
            }
        }
        return new ArrayList<>(out);
    }

    private static boolean eqJson(JsonNode a, JsonNode b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a.isNull() && b.isNull()) return true;
        return a.equals(b);
    }

    private static Iterator<String> unionFields(JsonNode a, JsonNode b) {
        Set<String> u = new LinkedHashSet<>();
        a.fieldNames().forEachRemaining(u::add);
        b.fieldNames().forEachRemaining(u::add);
        return u.iterator();
    }

    // ── DTO ─────────────────────────────────────────────────────────────────────

    public record Result(String fromVersion, String toVersion, List<Entry> entries, Summary summary) {}
    public record Entry(String op, List<String> keyParts, List<String> changedFields, JsonNode before, JsonNode after) {}
    public record Summary(int added, int changed, int removed, int moved) {}
}
