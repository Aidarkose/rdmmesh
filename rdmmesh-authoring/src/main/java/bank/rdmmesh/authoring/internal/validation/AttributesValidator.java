package bank.rdmmesh.authoring.internal.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

/**
 * Валидирует {@code CodeItem.attributes} (произвольный JSON-объект) против активной
 * {@code CodeSetSchema.json_schema}. SPEC §2.2 этап 2 + §3.5 PUT schema. Использует
 * {@code networknt/json-schema-validator} (1.5.x), draft-07 — совпадает с тем,
 * на чём писались {@code rdmmesh-spec/schema/*.json}.
 *
 * <p>Скомпилированные схемы кэшируются по {@code (codesetId, schemaVersion)} —
 * compile дорогой, схема CodeSet'а одна и та же на тысячах items.
 */
public final class AttributesValidator {

    private final ObjectMapper json;
    private final JsonSchemaFactory factory;
    private final java.util.Map<CacheKey, JsonSchema> cache = new ConcurrentHashMap<>();

    public AttributesValidator(ObjectMapper json) {
        this.json = json;
        this.factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    }

    /**
     * Валидирует {@code attributes} (Map) против схемы. Возвращает список ошибок —
     * пустой если всё ок. Schema-text может быть пустым ({@code "{}"}) — в этом
     * случае accept'им любой объект.
     */
    public List<String> validate(
            java.util.UUID codesetId,
            int schemaVersion,
            String schemaJsonText,
            Map<String, Object> attributes) {
        try {
            JsonSchema schema = cache.computeIfAbsent(
                    new CacheKey(codesetId, schemaVersion),
                    k -> compile(schemaJsonText));
            JsonNode payload = attributes == null
                    ? json.createObjectNode()
                    : json.valueToTree(attributes);
            Set<ValidationMessage> errs = schema.validate(payload);
            if (errs.isEmpty()) return List.of();
            List<String> out = new ArrayList<>(errs.size());
            for (ValidationMessage v : errs) out.add(v.getMessage());
            return out;
        } catch (RuntimeException e) {
            // Сюда попадаем, если сама схема некорректна — возвращаем как ошибку валидации,
            // не падаем 500'кой. В UI Schema Designer'а это станет понятным сообщением.
            return List.of("Schema is invalid: " + e.getMessage());
        }
    }

    private JsonSchema compile(String schemaJsonText) {
        try {
            JsonNode node = (schemaJsonText == null || schemaJsonText.isBlank())
                    ? json.createObjectNode()
                    : json.readTree(schemaJsonText);
            return factory.getSchema(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot parse JSON Schema: " + e.getMessage(), e);
        }
    }

    /** Сбрасывает кэш для конкретной schema-revision (на случай PUT новой schema). */
    public void invalidate(java.util.UUID codesetId, int schemaVersion) {
        cache.remove(new CacheKey(codesetId, schemaVersion));
    }

    private record CacheKey(java.util.UUID codesetId, int schemaVersion) {}
}
