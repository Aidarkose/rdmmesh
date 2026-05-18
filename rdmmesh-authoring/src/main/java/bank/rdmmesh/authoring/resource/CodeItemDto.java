package bank.rdmmesh.authoring.resource;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/**
 * Wire DTO для CodeItem'ов. Не используется сгенерированный {@code bank.rdmmesh.spec.entity.CodeItem}
 * по той же причине, что и {@code CodeSetSchemaDto} в catalog'е (E3 §1.4): jsonschema2pojo
 * генерирует строго-типизированный POJO для {@code attributes} и {@code parent_ref}, что
 * не подходит для произвольных JSON-объектов. Здесь — Map/List со свободной структурой.
 *
 * <p>Сериализация совпадает 1:1 с rdmmesh-spec/schema/entity/code-item.json по именам и
 * структуре полей.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonPropertyOrder({
        "id", "version_id", "key_parts", "label_ru", "label_en",
        "description_ru", "description_en", "parent_key", "parent_ref",
        "attributes", "order_index", "status",
        "effective_from", "effective_to", "system_from", "system_to", "row_version"})
public record CodeItemDto(
        @JsonProperty("id") String id,
        @JsonProperty("version_id") String versionId,
        @JsonProperty("key_parts") List<String> keyParts,
        @JsonProperty("label_ru") String labelRu,
        @JsonProperty("label_en") String labelEn,
        @JsonProperty("description_ru") String descriptionRu,
        @JsonProperty("description_en") String descriptionEn,
        @JsonProperty("parent_key") List<String> parentKey,
        @JsonProperty("parent_ref") Map<String, Object> parentRef,
        @JsonProperty("attributes") Map<String, Object> attributes,
        @JsonProperty("order_index") Integer orderIndex,
        @JsonProperty("status") String status,
        @JsonProperty("effective_from") String effectiveFrom,
        @JsonProperty("effective_to") String effectiveTo,
        @JsonProperty("system_from") String systemFrom,
        @JsonProperty("system_to") String systemTo,
        @JsonProperty("row_version") Integer rowVersion) {}
