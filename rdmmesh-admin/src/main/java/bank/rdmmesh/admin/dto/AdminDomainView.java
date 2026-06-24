package bank.rdmmesh.admin.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Расширенная view модель catalog.domain для admin-эндпоинтов. В отличие от
 * {@code bank.rdmmesh.spec.entity.Domain} (JSON Schema, контракт для read-API consumer'ов)
 * — несёт {@code master}, {@code local_overrides}, {@code external_refs}, {@code last_om_sync_at},
 * {@code deleted_in_om_at} (E18, ADR-0011). Не регенерируем spec, чтобы не задеть downstream'ы
 * (TS codegen, Pydantic, OM ingestion).
 */
public record AdminDomainView(
        @JsonProperty("id") String id,
        @JsonProperty("om_domain_id") String omDomainId,
        @JsonProperty("name") String name,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("description") String description,
        @JsonProperty("label_ru") String labelRu,
        @JsonProperty("label_en") String labelEn,
        @JsonProperty("tags") List<String> tags,
        @JsonProperty("master") String master,
        @JsonProperty("local_overrides") String localOverridesJson,
        @JsonProperty("external_refs") String externalRefsJson,
        @JsonProperty("last_om_sync_at") Instant lastOmSyncAt,
        @JsonProperty("deleted_in_om_at") Instant deletedInOmAt,
        @JsonProperty("active_codeset_count") long activeCodeSetCount,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("updated_at") Instant updatedAt,
        @JsonProperty("deleted_at") Instant deletedAt) {

    /** Helper для конверсии UUID → строка (или null). */
    public static String uuidOrNull(UUID id) {
        return id == null ? null : id.toString();
    }
}
