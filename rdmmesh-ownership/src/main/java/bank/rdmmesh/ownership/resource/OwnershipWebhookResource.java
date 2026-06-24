package bank.rdmmesh.ownership.resource;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.ownership.internal.webhook.HmacVerifier;
import bank.rdmmesh.ownership.internal.webhook.OwnershipWebhookService;
import bank.rdmmesh.ownership.internal.webhook.OwnershipWebhookService.Result;
import bank.rdmmesh.spec.events.OwnershipChangedEvent;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * HTTP endpoint для OM Event Subscription. Принимает {@link OwnershipChangedEvent} как
 * raw bytes (чтобы HMAC валидировался по байтам, точно как их подписал OM), валидирует
 * подпись из header'а {@code X-OM-Signature: sha256=<hex>}, парсит JSON и делегирует в
 * {@link OwnershipWebhookService}.
 *
 * <p>Webhook не защищён JWT — аутентификация целиком на HMAC.
 */
@Path("/webhooks/om/ownership")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public final class OwnershipWebhookResource {

    private static final Logger log = LoggerFactory.getLogger(OwnershipWebhookResource.class);

    private final HmacVerifier hmac;
    private final OwnershipWebhookService service;
    private final ObjectMapper json;

    public OwnershipWebhookResource(
            HmacVerifier hmac, OwnershipWebhookService service, ObjectMapper json) {
        this.hmac = hmac;
        this.service = service;
        this.json = json;
    }

    @POST
    public Response receive(
            @HeaderParam("X-OM-Signature") String signatureHeader,
            byte[] rawBody) {
        if (rawBody == null || rawBody.length == 0) {
            return error(Response.Status.BAD_REQUEST, "empty body");
        }
        if (!hmac.verify(signatureHeader, rawBody)) {
            log.warn("ownership-webhook: HMAC mismatch (header present={})",
                    signatureHeader != null);
            return error(Response.Status.UNAUTHORIZED, "invalid X-OM-Signature");
        }

        OwnershipChangedEvent event;
        try {
            event = json.readValue(rawBody, OwnershipChangedEvent.class);
        } catch (Exception e) {
            return error(Response.Status.BAD_REQUEST, "malformed payload: " + e.getMessage());
        }
        if (event.getEventId() == null || event.getEventId().isBlank()) {
            return error(Response.Status.BAD_REQUEST, "event_id is required");
        }
        if (event.getEntityType() == null) {
            return error(Response.Status.BAD_REQUEST, "entity_type is required");
        }

        try {
            Result result = service.handle(event, rawBody);
            Response.Status status = switch (result.outcome()) {
                case APPLIED, DUPLICATE, IGNORED, UNKNOWN_ASSET, UNSUPPORTED -> Response.Status.OK;
                case BAD_REQUEST -> Response.Status.BAD_REQUEST;
            };
            return Response.status(status).entity(toMap(result)).build();
        } catch (IllegalArgumentException e) {
            return error(Response.Status.BAD_REQUEST, e.getMessage());
        }
    }

    private static Map<String, Object> toMap(Result r) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("outcome", r.outcome().name());
        m.put("event_id", r.eventId());
        if (r.entityRef() != null) m.put("entity_ref", r.entityRef());
        if (r.summary() != null) m.put("summary", r.summary());
        if (r.note() != null) m.put("note", r.note());
        return m;
    }

    private Response error(Response.Status status, String reason) {
        try {
            return Response.status(status)
                    .entity(json.writeValueAsString(Map.of("error", reason)))
                    .type(MediaType.APPLICATION_JSON)
                    .build();
        } catch (JsonProcessingException e) {
            return Response.status(status).entity("{\"error\":\"" + reason + "\"}").build();
        }
    }
}
