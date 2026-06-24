package bank.rdmmesh.authoring.resource;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.annotation.JsonProperty;

import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.authoring.internal.KeyEncoding;
import bank.rdmmesh.authoring.internal.service.AuthoringService;
import bank.rdmmesh.authoring.internal.service.AuthoringService.BulkResult;
import bank.rdmmesh.authoring.internal.service.AuthoringService.ItemPatch;
import bank.rdmmesh.authoring.internal.service.AuthoringService.ItemsPage;
import bank.rdmmesh.authoring.internal.service.AuthoringService.NewItem;
import bank.rdmmesh.authoring.internal.service.AuthoringService.OptimisticLockException;
import bank.rdmmesh.authoring.internal.service.AuthoringService.ValidationException;

import io.dropwizard.auth.Auth;

/**
 * REST для CodeItem'ов внутри версии (SPEC §3.5):
 * <ul>
 *   <li>{@code GET    /versions/{id}/items?page=&size=}</li>
 *   <li>{@code POST   /versions/{id}/items}</li>
 *   <li>{@code GET    /versions/{id}/items/{key}} — composite key как base64url(JSON)</li>
 *   <li>{@code PATCH  /versions/{id}/items/{itemId}} — by item.id (UUID)</li>
 *   <li>{@code DELETE /versions/{id}/items/{itemId}}</li>
 *   <li>{@code POST   /versions/{id}/items/bulk}     — JSON array</li>
 *   <li>{@code POST   /versions/{id}/items/bulk-csv} — text/csv body</li>
 *   <li>{@code POST   /versions/{id}/items/bulk-xlsx} — xlsx body (новая фича)</li>
 * </ul>
 *
 * <p><b>Composite key encoding.</b> Path-сегмент {@code {key}} ожидает
 * {@code base64url(JSON.stringify(["KZ"]))} (или multi: {@code base64url(["RETAIL","BB","12M"])}).
 * Это даёт стабильные URL'ы и не зависит от любых разделителей внутри ключевых частей.
 *
 * <p><b>UUID vs key.</b> {@code GET}/{@code lookup} удобнее по key (читабельный смысл),
 * a {@code PATCH}/{@code DELETE} — по {@code item.id} (UUID), потому что в одной DRAFT-версии
 * могут быть несколько items с одинаковым key (например, после temporal-расширения в V13).
 * В MVP это эквивалентно одному-к-одному, но контракт устойчив к изменению.
 */
@Path("/versions/{versionId}/items")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public final class CodeItemResource {

    private final AuthoringService authoring;

    public CodeItemResource(AuthoringService authoring) {
        this.authoring = authoring;
    }

    @GET
    public ItemsPage list(
            @Auth RdmmeshPrincipal principal,
            @PathParam("versionId") String versionId,
            @QueryParam("page") @jakarta.ws.rs.DefaultValue("0") int page,
            @QueryParam("size") @jakarta.ws.rs.DefaultValue("100") int size) {
        return authoring.listItems(parseUuid(versionId, "versionId"), page, size);
    }

    @POST
    @RolesAllowed({"RDM_AUTHOR", "RDM_ADMIN"})
    public Response create(
            @Auth RdmmeshPrincipal principal,
            @PathParam("versionId") String versionId,
            @Valid @NotNull NewItemRequest req) {
        UUID v = parseUuid(versionId, "versionId");
        try {
            CodeItemDto created = authoring.addItem(v, req.toService(), principal.omUserId());
            return Response.status(Response.Status.CREATED).entity(created).build();
        } catch (ValidationException e) {
            throw new WebApplicationException(e.getMessage(), 422);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
    }

    @GET
    @Path("/{key}")
    public CodeItemDto getByKey(
            @Auth RdmmeshPrincipal principal, @PathParam("versionId") String versionId, @PathParam("key") String key) {
        UUID v = parseUuid(versionId, "versionId");
        List<String> parts;
        try {
            parts = KeyEncoding.decode(key);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
        return authoring.findItemByKey(v, parts).orElseThrow(() -> new NotFoundException("item with key " + parts));
    }

    @PATCH
    @Path("/{itemId}")
    @RolesAllowed({"RDM_AUTHOR", "RDM_ADMIN"})
    public CodeItemDto patch(
            @Auth RdmmeshPrincipal principal,
            @PathParam("versionId") String versionId,
            @PathParam("itemId") String itemId,
            @Valid @NotNull ItemPatchRequest req) {
        UUID v = parseUuid(versionId, "versionId");
        UUID item = parseUuid(itemId, "itemId");
        try {
            return authoring.updateItem(v, item, req.toService(), principal.omUserId());
        } catch (OptimisticLockException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        } catch (ValidationException e) {
            throw new WebApplicationException(e.getMessage(), 422);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.NOT_FOUND);
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
    }

    @DELETE
    @Path("/{itemId}")
    @RolesAllowed({"RDM_AUTHOR", "RDM_ADMIN"})
    public Response delete(
            @Auth RdmmeshPrincipal principal,
            @PathParam("versionId") String versionId,
            @PathParam("itemId") String itemId) {
        UUID v = parseUuid(versionId, "versionId");
        UUID item = parseUuid(itemId, "itemId");
        boolean ok;
        try {
            ok = authoring.deleteItem(v, item, principal.omUserId());
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
        if (!ok) throw new NotFoundException("item " + itemId);
        return Response.noContent().build();
    }

    /**
     * E21 — clear-all items DRAFT-версии. Защищено обязательным
     * {@code ?confirm=clear-all} — это значит, что случайный {@code curl -X DELETE}
     * на коллекцию items без параметра вернёт 400, а не молчаливое уничтожение.
     * UI-кнопка добавляет параметр после двухступенчатого подтверждения.
     */
    @DELETE
    @RolesAllowed({"RDM_AUTHOR", "RDM_ADMIN"})
    public ClearAllResponse clearAll(
            @Auth RdmmeshPrincipal principal,
            @PathParam("versionId") String versionId,
            @QueryParam("confirm") String confirm) {
        if (!"clear-all".equals(confirm)) {
            throw new WebApplicationException(
                    "confirm=clear-all is required to wipe the version", Response.Status.BAD_REQUEST);
        }
        UUID v = parseUuid(versionId, "versionId");
        int deleted;
        try {
            deleted = authoring.clearAllItems(v, principal.omUserId());
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.NOT_FOUND);
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
        return new ClearAllResponse(deleted);
    }

    public record ClearAllResponse(@JsonProperty("deleted") int deleted) {}

    @POST
    @Path("/bulk")
    @RolesAllowed({"RDM_AUTHOR", "RDM_ADMIN"})
    public Response bulkJson(
            @Auth RdmmeshPrincipal principal,
            @PathParam("versionId") String versionId,
            @Valid @NotNull List<NewItemRequest> rows) {
        UUID v = parseUuid(versionId, "versionId");
        List<NewItem> items = new ArrayList<>(rows.size());
        for (NewItemRequest r : rows) items.add(r.toService());
        BulkResult res;
        try {
            res = authoring.bulkUpsertJson(v, items, principal.omUserId());
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
        return Response.status(res.status().equals("APPLIED") ? 200 : 422)
                .entity(res)
                .build();
    }

    @POST
    @Path("/bulk-csv")
    @Consumes("text/csv")
    @RolesAllowed({"RDM_AUTHOR", "RDM_ADMIN"})
    public Response bulkCsv(
            @Auth RdmmeshPrincipal principal, @PathParam("versionId") String versionId, InputStream body) {
        UUID v = parseUuid(versionId, "versionId");
        BulkResult res;
        try {
            res = authoring.bulkUpsertCsv(v, body, principal.omUserId());
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
        return Response.status(res.status().equals("APPLIED") ? 200 : 422)
                .entity(res)
                .build();
    }

    @POST
    @Path("/bulk-xlsx")
    @Consumes("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    @RolesAllowed({"RDM_AUTHOR", "RDM_ADMIN"})
    public Response bulkXlsx(
            @Auth RdmmeshPrincipal principal,
            @PathParam("versionId") String versionId,
            // E19 Commit 3 — pivot-режим: ?layout=pivot&horizon=1Y&row_residual_policy=implicit_default
            @QueryParam("layout") String layout,
            @QueryParam("horizon") String horizon,
            @QueryParam("row_residual_policy") String rowResidualPolicy,
            InputStream body) {
        UUID v = parseUuid(versionId, "versionId");
        BulkResult res;
        try {
            if (layout != null && layout.equalsIgnoreCase("pivot")) {
                if (horizon == null || horizon.isBlank()) {
                    throw new WebApplicationException(
                            "layout=pivot requires ?horizon=<value> (e.g. 1Y)",
                            Response.Status.BAD_REQUEST);
                }
                bank.rdmmesh.authoring.internal.xlsx.MatrixPivotSheetParser.RowResidualPolicy pol;
                try {
                    pol = bank.rdmmesh.authoring.internal.xlsx.MatrixPivotSheetParser.RowResidualPolicy
                            .parseOrDefault(rowResidualPolicy);
                } catch (IllegalArgumentException e) {
                    throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
                }
                res = authoring.bulkUpsertXlsxPivot(v, body, horizon, pol, principal.omUserId());
            } else {
                // Default / layout=long — существующее поведение E15.
                res = authoring.bulkUpsertXlsx(v, body, principal.omUserId());
            }
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
        return Response.status(res.status().equals("APPLIED") ? 200 : 422)
                .entity(res)
                .build();
    }

    private static UUID parseUuid(String s, String field) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(field + " must be a UUID", Response.Status.BAD_REQUEST);
        }
    }

    // ── request DTOs ────────────────────────────────────────────────────────────

    public static final class NewItemRequest {
        @JsonProperty("key_parts")
        @NotNull
        @NotEmpty
        public List<String> keyParts;

        @JsonProperty("parent_key")
        public List<String> parentKey;

        @JsonProperty("parent_ref")
        public Map<String, Object> parentRef;

        @JsonProperty("label_ru")
        public String labelRu;

        @JsonProperty("label_en")
        public String labelEn;

        @JsonProperty("description_ru")
        public String descriptionRu;

        @JsonProperty("description_en")
        public String descriptionEn;

        @JsonProperty("attributes")
        public Map<String, Object> attributes;

        @JsonProperty("order_index")
        public Integer orderIndex;

        @JsonProperty("status")
        public String status;

        @JsonProperty("effective_from")
        public String effectiveFrom;

        @JsonProperty("effective_to")
        public String effectiveTo;

        NewItem toService() {
            return new NewItem(
                    keyParts,
                    parentKey,
                    parentRef == null ? null : new LinkedHashMap<>(parentRef),
                    labelRu,
                    labelEn,
                    descriptionRu,
                    descriptionEn,
                    attributes,
                    orderIndex,
                    status,
                    parseDate(effectiveFrom, "effective_from"),
                    parseDate(effectiveTo, "effective_to"));
        }
    }

    public static final class ItemPatchRequest {
        @JsonProperty("expected_row_version")
        public Integer expectedRowVersion;

        @JsonProperty("parent_key")
        public List<String> parentKey;

        @JsonProperty("parent_ref")
        public Map<String, Object> parentRef;

        @JsonProperty("label_ru")
        public String labelRu;

        @JsonProperty("label_en")
        public String labelEn;

        @JsonProperty("description_ru")
        public String descriptionRu;

        @JsonProperty("description_en")
        public String descriptionEn;

        @JsonProperty("attributes")
        public Map<String, Object> attributes;

        @JsonProperty("order_index")
        public Integer orderIndex;

        @JsonProperty("status")
        public String status;

        @JsonProperty("effective_from")
        public String effectiveFrom;

        @JsonProperty("effective_to")
        public String effectiveTo;

        ItemPatch toService() {
            if (expectedRowVersion == null) {
                throw new WebApplicationException(
                        "expected_row_version is required for PATCH (optimistic lock)", Response.Status.BAD_REQUEST);
            }
            return new ItemPatch(
                    expectedRowVersion,
                    parentKey,
                    parentRef == null ? null : new LinkedHashMap<>(parentRef),
                    labelRu,
                    labelEn,
                    descriptionRu,
                    descriptionEn,
                    attributes,
                    orderIndex,
                    status,
                    parseDate(effectiveFrom, "effective_from"),
                    parseDate(effectiveTo, "effective_to"));
        }
    }

    private static LocalDate parseDate(String text, String field) {
        if (text == null || text.isBlank()) return null;
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new WebApplicationException(
                    field + " must be ISO date (YYYY-MM-DD), got '" + text + "'", Response.Status.BAD_REQUEST);
        }
    }
}
