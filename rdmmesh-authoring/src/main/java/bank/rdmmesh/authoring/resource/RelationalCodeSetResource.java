package bank.rdmmesh.authoring.resource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
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
import bank.rdmmesh.authoring.internal.relational.RelationalStoreService;
import bank.rdmmesh.authoring.internal.relational.RelationalStoreService.ProvisionResult;
import bank.rdmmesh.authoring.internal.relational.RelationalStoreService.PublishResult;

import io.dropwizard.auth.Auth;

/**
 * REST для relational store (спайк, модель версионности C — draft + current).
 *
 * <ul>
 *   <li>{@code POST   /relational/codesets/{id}/provision}              — CREATE TABLE draft+current</li>
 *   <li>{@code POST   /relational/codesets/{id}/draft-rows?version_id=} — upsert строки черновика</li>
 *   <li>{@code DELETE /relational/codesets/{id}/draft-rows?version_id=} — удалить строку черновика по ключу</li>
 *   <li>{@code GET    /relational/codesets/{id}/draft-rows?version_id=} — строки черновика</li>
 *   <li>{@code POST   /relational/codesets/{id}/publish?version_id=}    — пересобрать current из draft</li>
 *   <li>{@code GET    /relational/codesets/{id}/rows}                   — текущий PUBLISHED-снапшот (raw rows)</li>
 *   <li>{@code GET    /relational/codesets/{id}/items}                 — PUBLISHED-снапшот → CodeItemDto (Stage 3)</li>
 *   <li>{@code GET    /relational/codesets/{id}/draft-items?version_id=} — черновик → CodeItemDto (Stage 3)</li>
 *   <li>{@code GET    /relational/codesets/{id}/published-items?version_id=} — снимок PUBLISHED-версии (Stage 7a)</li>
 *   <li>{@code GET    /relational/codesets/{id}/closure[?version_id=]}   — closure иерархии (Stage 4)</li>
 *   <li>{@code GET    /relational/codesets/{id}/cycles[?version_id=]}    — ключи в циклах parent_key (Stage 4)</li>
 *   <li>{@code GET    /relational/codesets/{id}/content-hash[?version_id=]} — content_hash из rd_data (Stage 5)</li>
 *   <li>{@code GET    /relational/codesets/{id}/diff?from_version_id=&to_version_id=} — колоночный diff (Stage 5)</li>
 *   <li>{@code POST   /relational/codesets/{id}/foreign-keys}        — материализовать FK из E25 (Stage 6)</li>
 * </ul>
 *
 * <p>Префикс {@code /relational} (а не {@code /codesets}) — чтобы не пересекаться с
 * catalog'овым root-resource'ом {@code CodeSetResource} в Jersey-роутинге.
 */
@Path("/relational/codesets/{id}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public final class RelationalCodeSetResource {

    private final RelationalStoreService store;

    public RelationalCodeSetResource(RelationalStoreService store) {
        this.store = store;
    }

    @POST
    @Path("/provision")
    @RolesAllowed({"RDM_AUTHOR", "RDM_SCHEMA_DESIGNER", "RDM_ADMIN"})
    public ProvisionResult provision(@Auth RdmmeshPrincipal principal, @PathParam("id") String id) {
        try {
            return store.provision(parseUuid(id));
        } catch (IllegalArgumentException e) {
            throw badRequest(e);
        }
    }

    @POST
    @Path("/draft-rows")
    @RolesAllowed({"RDM_AUTHOR", "RDM_SCHEMA_DESIGNER", "RDM_ADMIN"})
    public Response upsertDraftRow(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @QueryParam("version_id") String versionId,
            @NotNull Map<String, Object> row) {
        parseUuid(id);
        try {
            store.upsertDraftRow(requireVersion(versionId), row);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            throw badRequest(e);
        } catch (IllegalStateException e) {
            throw conflict(e);
        }
    }

    @DELETE
    @Path("/draft-rows")
    @RolesAllowed({"RDM_AUTHOR", "RDM_SCHEMA_DESIGNER", "RDM_ADMIN"})
    public Response deleteDraftRow(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @QueryParam("version_id") String versionId,
            @NotNull Map<String, Object> keyCells) {
        parseUuid(id);
        try {
            store.deleteDraftRow(requireVersion(versionId), keyCells);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            throw badRequest(e);
        } catch (IllegalStateException e) {
            throw conflict(e);
        }
    }

    @GET
    @Path("/draft-rows")
    public List<Map<String, Object>> listDraftRows(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @QueryParam("version_id") String versionId) {
        parseUuid(id);
        try {
            return store.listDraftRows(requireVersion(versionId));
        } catch (IllegalStateException e) {
            throw conflict(e);
        }
    }

    @POST
    @Path("/publish")
    @RolesAllowed({"RDM_AUTHOR", "RDM_SCHEMA_DESIGNER", "RDM_ADMIN"})
    public PublishResult publish(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @QueryParam("version_id") String versionId) {
        UUID codesetId = parseUuid(id);
        UUID vId = requireVersion(versionId);
        try {
            PublishResult result = store.publish(vId);
            assertBelongs(result.codesetId(), codesetId, versionId, id);
            return result;
        } catch (IllegalArgumentException e) {
            throw badRequest(e);
        } catch (IllegalStateException e) {
            throw conflict(e);
        }
    }

    @GET
    @Path("/rows")
    public List<Map<String, Object>> listCurrentRows(
            @Auth RdmmeshPrincipal principal, @PathParam("id") String id) {
        try {
            return store.listCurrentRows(parseUuid(id));
        } catch (IllegalStateException e) {
            throw conflict(e);
        }
    }

    @GET
    @Path("/items")
    public List<CodeItemDto> listCurrentItems(
            @Auth RdmmeshPrincipal principal, @PathParam("id") String id) {
        try {
            return store.listCurrentItems(parseUuid(id));
        } catch (IllegalStateException e) {
            throw conflict(e);
        }
    }

    @GET
    @Path("/draft-items")
    public List<CodeItemDto> listDraftItems(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @QueryParam("version_id") String versionId) {
        parseUuid(id);
        try {
            return store.listDraftItems(requireVersion(versionId));
        } catch (IllegalStateException e) {
            throw conflict(e);
        }
    }

    @GET
    @Path("/published-items")
    public List<CodeItemDto> listPublishedItems(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @QueryParam("version_id") String versionId) {
        parseUuid(id);
        try {
            return store.listPublishedItems(requireVersion(versionId));
        } catch (IllegalStateException e) {
            throw conflict(e);
        }
    }

    @GET
    @Path("/closure")
    public List<RelationalStoreService.ClosureRow> closure(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @QueryParam("version_id") String versionId) {
        parseUuid(id);
        try {
            return versionId == null || versionId.isBlank()
                    ? store.currentClosure(parseUuid(id))
                    : store.draftClosure(parseUuid(versionId));
        } catch (IllegalStateException e) {
            throw conflict(e);
        }
    }

    @GET
    @Path("/cycles")
    public List<List<String>> cycles(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @QueryParam("version_id") String versionId) {
        parseUuid(id);
        try {
            return versionId == null || versionId.isBlank()
                    ? store.currentCycles(parseUuid(id))
                    : store.draftCycles(parseUuid(versionId));
        } catch (IllegalStateException e) {
            throw conflict(e);
        }
    }

    @POST
    @Path("/foreign-keys")
    @RolesAllowed({"RDM_AUTHOR", "RDM_SCHEMA_DESIGNER", "RDM_ADMIN"})
    public RelationalStoreService.ForeignKeyReport applyForeignKeys(
            @Auth RdmmeshPrincipal principal, @PathParam("id") String id) {
        try {
            return store.applyForeignKeys(parseUuid(id));
        } catch (IllegalArgumentException e) {
            throw badRequest(e);
        } catch (IllegalStateException e) {
            throw conflict(e);
        }
    }

    @GET
    @Path("/sync-status")
    public SyncStatusResponse syncStatus(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @QueryParam("version_id") String versionId) {
        parseUuid(id);
        UUID vid = parseUuid(versionId);
        return store.syncStatus(vid)
                .map(r -> new SyncStatusResponse(
                        r.versionId().toString(),
                        r.codesetId().toString(),
                        r.state(),
                        r.reason(),
                        r.updatedAt() == null ? null : r.updatedAt().toString()))
                .orElse(new SyncStatusResponse(versionId, id, "UNKNOWN", null, null));
    }

    /** Ответ sync-status (Stage 7 A): state ∈ OK | STALE | BLOCKED | UNKNOWN. */
    public record SyncStatusResponse(
            @JsonProperty("version_id") String versionId,
            @JsonProperty("codeset_id") String codesetId,
            @JsonProperty("state") String state,
            @JsonProperty("reason") String reason,
            @JsonProperty("updated_at") String updatedAt) {}

    @GET
    @Path("/content-hash")
    public ContentHashResponse contentHash(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @QueryParam("version_id") String versionId) {
        parseUuid(id);
        try {
            String hash = versionId == null || versionId.isBlank()
                    ? store.currentContentHash(parseUuid(id))
                    : store.draftContentHash(parseUuid(versionId));
            return new ContentHashResponse(hash);
        } catch (IllegalStateException e) {
            throw conflict(e);
        }
    }

    public record ContentHashResponse(@JsonProperty("content_hash") String contentHash) {}

    @GET
    @Path("/diff")
    public RelationalStoreService.RelDiffSummary diff(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @QueryParam("from_version_id") String fromVersionId,
            @QueryParam("to_version_id") String toVersionId) {
        parseUuid(id);
        UUID from = requireVersion(fromVersionId);
        UUID to = requireVersion(toVersionId);
        try {
            return store.diff(from, to);
        } catch (IllegalArgumentException e) {
            throw badRequest(e);
        } catch (IllegalStateException e) {
            throw conflict(e);
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static void assertBelongs(UUID actual, UUID expected, String versionId, String id) {
        if (!actual.equals(expected)) {
            throw new WebApplicationException(
                    "version " + versionId + " does not belong to codeset " + id,
                    Response.Status.BAD_REQUEST);
        }
    }

    private static UUID requireVersion(String versionId) {
        if (versionId == null || versionId.isBlank()) {
            throw new WebApplicationException(
                    "version_id query param is required", Response.Status.BAD_REQUEST);
        }
        return parseUuid(versionId);
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException ex) {
            throw new WebApplicationException("must be a UUID, got '" + s + "'",
                    Response.Status.BAD_REQUEST);
        }
    }

    private static WebApplicationException badRequest(RuntimeException e) {
        return new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
    }

    private static WebApplicationException conflict(RuntimeException e) {
        return new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
    }
}
