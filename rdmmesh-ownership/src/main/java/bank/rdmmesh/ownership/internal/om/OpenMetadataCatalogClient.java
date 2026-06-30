package bank.rdmmesh.ownership.internal.om;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Клиент к нативному «из коробки» OpenMetadata REST API для pull'а доменов и ролей.
 * Вызывается из {@code CatalogSyncService} после получения тонкого уведомления от OM
 * Alert (см. {@code CatalogSyncWebhookResource}). Аутентификация — Bearer bot-token
 * (стандартный secure REST OM). Паттерн повторяет {@code OpenMetadataUserClient}.
 *
 * <p>Эндпоинты:
 * <ul>
 *   <li>{@code GET /api/v1/domains?limit=&fields=description} — список доменов;</li>
 *   <li>{@code GET /api/v1/roles?limit=} — список ролей.</li>
 * </ul>
 */
public final class OpenMetadataCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(OpenMetadataCatalogClient.class);
    private static final int PAGE_LIMIT = 1000;

    private final URI baseUri;
    private final String botToken;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Duration requestTimeout;

    public OpenMetadataCatalogClient(
            String baseUrl, String botToken, Duration connectTimeout, Duration requestTimeout) {
        String normalized = Objects.requireNonNull(baseUrl, "baseUrl").endsWith("/")
                ? baseUrl : baseUrl + "/";
        this.baseUri = URI.create(normalized);
        this.botToken = Objects.requireNonNull(botToken, "botToken");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.json = new ObjectMapper();
    }

    /**
     * Сущность OM (домен или роль) в форме, достаточной для зеркала. {@code parentId} —
     * om_domain_id родителя для поддомена (только у доменов; null у ролей и корневых доменов).
     */
    public record OmEntity(
            String id, String name, String displayName, String description, String parentId) {}

    /** Список всех доменов OM (с parent для иерархии). Пустой список при ошибке (с warn). */
    public List<OmEntity> listDomains() {
        return list("api/v1/domains?fields=description,parent&limit=" + PAGE_LIMIT, "domains");
    }

    /** Список всех ролей OM. Пустой список при ошибке (с warn). */
    public List<OmEntity> listRoles() {
        return list("api/v1/roles?limit=" + PAGE_LIMIT, "roles");
    }

    private List<OmEntity> list(String path, String what) {
        var request = HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + botToken)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                log.warn("OM: GET {} -> HTTP {} (body: {})", path, status,
                        abbreviate(response.body()));
                return List.of();
            }
            JsonNode root = json.readTree(response.body());
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                log.warn("OM: ответ {} не содержит массива data", what);
                return List.of();
            }
            List<OmEntity> out = new ArrayList<>(data.size());
            for (JsonNode n : data) {
                String id = text(n, "id");
                String name = text(n, "name");
                if (id == null || name == null) {
                    log.warn("OM: {} запись без id/name пропущена", what);
                    continue;
                }
                // parent.id — om_domain_id родителя у поддомена (у ролей/корней узла нет).
                String parentId = text(n.path("parent"), "id");
                out.add(new OmEntity(
                        id, name, text(n, "displayName"), text(n, "description"), parentId));
            }
            log.info("OM: pull {} — получено {}", what, out.size());
            return out;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            log.warn("OM: pull {} прерван", what);
            return List.of();
        } catch (Exception e) {
            log.warn("OM: pull {} не удался: {}", what, e.toString());
            return List.of();
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
