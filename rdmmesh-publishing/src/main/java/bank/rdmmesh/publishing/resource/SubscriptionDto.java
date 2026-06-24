package bank.rdmmesh.publishing.resource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import bank.rdmmesh.publishing.internal.outbound.dao.SubscriptionDao.SubscriptionRow;

/**
 * Wire-DTO для {@code GET/POST /api/v1/subscriptions}. Свой record — потому что
 * сгенерированный {@code WebhookSubscription} POJO жёстко требует
 * {@code id} как not-null для request body, а в POST id ещё не существует.
 *
 * <p>Поле {@code secret} — pointer на ключ (не сам ключ); SPEC §3.5 явно говорит
 * «secret value never returned by API».
 */
public final class SubscriptionDto {

    private SubscriptionDto() {}

    /** Request body для POST /subscriptions. */
    public record CreateRequest(
            @JsonProperty("url") String url,
            @JsonProperty("secret_id") String secretId,
            @JsonProperty("filter") Map<String, Object> filter,
            @JsonProperty("active") Boolean active) {}

    /** Response body для всех чтений. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record View(
            @JsonProperty("id") UUID id,
            @JsonProperty("url") String url,
            @JsonProperty("secret_id") String secretId,
            @JsonProperty("filter") Map<String, Object> filter,
            @JsonProperty("active") boolean active,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("created_by") UUID createdBy,
            @JsonProperty("last_delivery_at") Instant lastDeliveryAt,
            @JsonProperty("last_delivery_status") String lastDeliveryStatus) {

        @SuppressWarnings("unchecked")
        public static View from(SubscriptionRow row, ObjectMapper json) {
            Map<String, Object> filter = Map.of();
            if (row.filterJson() != null && !row.filterJson().isBlank()) {
                try {
                    filter = json.readValue(row.filterJson(), Map.class);
                } catch (Exception ignored) {
                    filter = Map.of();
                }
            }
            return new View(
                    row.id(),
                    row.url(),
                    row.secretId(),
                    filter,
                    row.active(),
                    row.createdAt(),
                    row.createdBy(),
                    row.lastDeliveryAt(),
                    row.lastDeliveryStatus());
        }
    }
}
