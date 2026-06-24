package bank.rdmmesh.admin.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AdminOwnershipView(
        @JsonProperty("id") String id,
        @JsonProperty("asset_id") String assetId,
        @JsonProperty("asset_type") String assetType,
        @JsonProperty("om_user_id") String omUserId,
        @JsonProperty("role") String role,
        @JsonProperty("origin") String origin,
        @JsonProperty("pinned_local") boolean pinnedLocal,
        @JsonProperty("is_provisional") boolean isProvisional,
        @JsonProperty("assigned_at") Instant assignedAt,
        @JsonProperty("assigned_by_user_id") String assignedByUserId) {}
