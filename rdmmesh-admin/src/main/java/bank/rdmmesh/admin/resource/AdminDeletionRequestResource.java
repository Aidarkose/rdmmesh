package bank.rdmmesh.admin.resource;

import java.util.List;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DefaultValue;
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

import bank.rdmmesh.admin.dto.AdminDeletionRequestView;
import bank.rdmmesh.admin.internal.AdminDeletionRequestService;
import bank.rdmmesh.admin.internal.AdminDeletionRequestService.ValidationException;
import bank.rdmmesh.api.security.RdmmeshPrincipal;
import io.dropwizard.auth.Auth;

/**
 * Admin-facing endpoints очереди заявок на удаление CodeSet (E22).
 *
 * <ul>
 *   <li>{@code GET  /admin/deletion-requests?status=PENDING} — очередь</li>
 *   <li>{@code POST /admin/deletion-requests/{id}:approve} — apprоve + soft-delete</li>
 *   <li>{@code POST /admin/deletion-requests/{id}:reject}  — reject с комментарием</li>
 * </ul>
 *
 * <p>Self-approval prevention: admin, который подал заявку, не может её approve/reject —
 * service бросает 409.
 */
@Path("/admin/deletion-requests")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("RDM_ADMIN")
public final class AdminDeletionRequestResource {

    private final AdminDeletionRequestService service;

    public AdminDeletionRequestResource(AdminDeletionRequestService service) {
        this.service = service;
    }

    @GET
    public List<AdminDeletionRequestView> list(
            @Auth RdmmeshPrincipal principal,
            @QueryParam("status") @DefaultValue("PENDING") String status) {
        try {
            return service.listByStatus(status);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    @POST
    @Path("/{id}:approve")
    public Response approve(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @Valid @NotNull ApproveRequest req) {
        UUID rid = parseUuid(id, "id");
        boolean force = req.forceArchive != null && req.forceArchive;
        try {
            service.approve(rid, req.decisionComment, force, principal.omUserId());
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.NOT_FOUND);
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
    }

    @POST
    @Path("/{id}:reject")
    public Response reject(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @Valid @NotNull RejectRequest req) {
        UUID rid = parseUuid(id, "id");
        try {
            service.reject(rid, req.decisionComment, principal.omUserId());
            return Response.noContent().build();
        } catch (ValidationException e) {
            throw new WebApplicationException(e.getMessage(), 422);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.NOT_FOUND);
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
    }

    private static UUID parseUuid(String s, String field) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new WebApplicationException(field + " must be a UUID", Response.Status.BAD_REQUEST);
        }
    }

    public static final class ApproveRequest {
        @JsonProperty("decision_comment")
        public String decisionComment;

        @JsonProperty("force_archive")
        public Boolean forceArchive;
    }

    public static final class RejectRequest {
        @JsonProperty("decision_comment")
        @NotEmpty
        public String decisionComment;
    }
}
