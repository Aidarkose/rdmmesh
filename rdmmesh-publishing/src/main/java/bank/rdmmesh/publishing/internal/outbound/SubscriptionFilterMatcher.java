package bank.rdmmesh.publishing.internal.outbound;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pure-функция: подходит ли событие под фильтр subscription'а.
 *
 * <p>Фильтр (rdmmesh-spec/schema/api/webhook-subscription.json,
 * см. SubscriptionFilter) — три необязательных списка: domains, codesets, events.
 * Семантика «AND по полям, OR внутри поля»:
 * <ul>
 *   <li>пустой/отсутствующий список → нет ограничения по этому полю;</li>
 *   <li>непустой → событие должно попасть хотя бы в один элемент.</li>
 * </ul>
 *
 * <p>Сравнение по {@code domain_name}/{@code codeset_name} — точное по lower-snake_case
 * (иные форматы в БД не появляются: см. JSON-Schema {@code qualified_name}).
 * Для events — {@code "VERSION_PUBLISHED"} | {@code "VERSION_DEPRECATED"}.
 *
 * <p>Парсинг JSON делается лениво (один раз на subscription) и реализуется снаружи —
 * этот класс получает уже распарсенные коллекции.
 */
public final class SubscriptionFilterMatcher {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private SubscriptionFilterMatcher() {}

    /** Match по уже распарсенному фильтру. */
    public static boolean matches(Filter filter, String domainName, String codesetName, String eventType) {
        if (filter == null) return true;
        if (!matchList(filter.domains, domainName)) return false;
        if (!matchList(filter.codesets, codesetName)) return false;
        if (!matchList(filter.events, eventType)) return false;
        return true;
    }

    /** Удобство: распарсить JSON-строку фильтра. {@code null}/{@code ""} → no-op-фильтр. */
    public static Filter parse(ObjectMapper json, String filterJson) {
        if (filterJson == null || filterJson.isBlank()) return Filter.empty();
        try {
            Map<String, Object> raw = json.readValue(filterJson, MAP_TYPE);
            return new Filter(
                    asList(raw.get("domains")),
                    asList(raw.get("codesets")),
                    asList(raw.get("events")));
        } catch (Exception e) {
            // Если фильтр в БД синтаксически плох — лучше доставлять (no-op-фильтр),
            // чем тихо потерять событие; ошибка будет видна в логе worker'а отдельной диагностикой.
            return Filter.empty();
        }
    }

    private static boolean matchList(List<String> list, String value) {
        if (list == null || list.isEmpty()) return true;
        if (value == null) return false;
        for (String it : list) {
            if (value.equals(it)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asList(Object raw) {
        if (raw instanceof List<?> l) {
            return l.stream().filter(o -> o != null).map(Object::toString).toList();
        }
        return List.of();
    }

    public record Filter(List<String> domains, List<String> codesets, List<String> events) {
        public static Filter empty() {
            return new Filter(List.of(), List.of(), List.of());
        }
    }
}
