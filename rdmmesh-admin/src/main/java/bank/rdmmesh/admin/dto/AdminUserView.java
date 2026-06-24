package bank.rdmmesh.admin.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record AdminUserView(
        @JsonProperty("om_user_id") String omUserId,
        @JsonProperty("username") String username,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("email") String email) {}
