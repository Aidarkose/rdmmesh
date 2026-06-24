package bank.rdmmesh.admin.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AdminTaskView(
        @JsonProperty("id") String id,
        @JsonProperty("task_type") String taskType,
        @JsonProperty("source_event_id") String sourceEventId,
        @JsonProperty("related_domain_id") String relatedDomainId,
        @JsonProperty("payload") String payloadJson,
        @JsonProperty("status") String status,
        @JsonProperty("created_at") Instant createdAt) {}
