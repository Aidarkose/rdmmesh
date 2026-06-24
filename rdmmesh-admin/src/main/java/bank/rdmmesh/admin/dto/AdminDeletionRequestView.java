package bank.rdmmesh.admin.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * REST-view заявки на удаление CodeSet'а (E22). Денормализует
 * {@code codeset.name} / {@code domain.name} и (для admin-очереди)
 * {@code requested_by_username} из {@code identity.rdm_user_mapping},
 * чтобы UI рисовал таблицу без N+1 запросов.
 */
public record AdminDeletionRequestView(
        @JsonProperty("id") String id,
        @JsonProperty("codeset_id") String codesetId,
        @JsonProperty("codeset_name") String codesetName,
        @JsonProperty("domain_id") String domainId,
        @JsonProperty("domain_name") String domainName,
        @JsonProperty("requested_by") String requestedBy,
        @JsonProperty("requested_by_username") String requestedByUsername,
        @JsonProperty("reason") String reason,
        @JsonProperty("status") String status,
        @JsonProperty("decided_by") String decidedBy,
        @JsonProperty("decided_by_username") String decidedByUsername,
        @JsonProperty("decision_comment") String decisionComment,
        @JsonProperty("created_at") Instant createdAt,
        @JsonProperty("decided_at") Instant decidedAt,
        @JsonProperty("codeset_deleted") boolean codesetDeleted,
        @JsonProperty("has_published_versions") boolean hasPublishedVersions) {}
