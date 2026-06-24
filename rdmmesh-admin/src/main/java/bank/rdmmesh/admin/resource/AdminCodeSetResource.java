package bank.rdmmesh.admin.resource;

import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.annotation.JsonProperty;

import bank.rdmmesh.admin.dto.AdminOwnershipView;
import bank.rdmmesh.admin.internal.AdminCodeSetService;
import bank.rdmmesh.admin.internal.AdminOwnershipService;
import bank.rdmmesh.admin.resource.AdminOwnershipResource.AssignRequest;
import bank.rdmmesh.api.security.RdmmeshPrincipal;
import io.dropwizard.auth.Auth;

@Path("/admin/codesets")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("RDM_ADMIN")
public final class AdminCodeSetResource {

    private final AdminCodeSetService service;
    private final AdminOwnershipService ownership;

    public AdminCodeSetResource(AdminCodeSetService service, AdminOwnershipService ownership) {
        this.service = service;
        this.ownership = ownership;
    }

    @GET
    @Path("/{id}/ownership")
    public List<AdminOwnershipView> listOwnership(
            @Auth RdmmeshPrincipal principal, @PathParam("id") String id) {
        return ownership.listForAsset(parseUuid(id, "id"), "CODESET");
    }

    @POST
    @Path("/{id}/ownership")
    public Response assignOwnership(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @jakarta.validation.Valid @jakarta.validation.constraints.NotNull AssignRequest req) {
        UUID uid = parseUuid(id, "id");
        UUID userId = parseUuid(req.omUserId, "om_user_id");
        try {
            AdminOwnershipView v = ownership.assign(
                    new AdminOwnershipService.NewAssignment(
                            uid, "CODESET", userId, req.role,
                            req.pinnedLocal != null && req.pinnedLocal),
                    principal.omUserId());
            return Response.status(Response.Status.CREATED).entity(v).build();
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    @POST
    @Path("/{id}:rename")
    public Response rename(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @Valid @NotNull RenameRequest req) {
        UUID uid = parseUuid(id, "id");
        boolean keep = req.keepAliasForIngestion == null || req.keepAliasForIngestion;
        try {
            service.rename(uid, req.newName, keep);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(e.getMessage());
        }
    }

    @DELETE
    @Path("/{id}")
    public Response delete(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @QueryParam("force_archive") Boolean force) {
        UUID uid = parseUuid(id, "id");
        try {
            service.delete(uid, force != null && force);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(e.getMessage());
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
    }

    private static UUID parseUuid(String s, String field) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new WebApplicationException(
                    field + " must be a UUID", Response.Status.BAD_REQUEST);
        }
    }

    public static final class RenameRequest {
        @JsonProperty("new_name")
        @NotEmpty
        @Pattern(regexp = "^[a-z][a-z0-9_]{0,63}$",
                message = "new_name must be lower snake_case, ≤64 chars")
        public String newName;

        /** По умолчанию true — старое имя сохраняется в aliases для ingestion-коннектора. */
        @JsonProperty("keep_alias_for_ingestion")
        public Boolean keepAliasForIngestion;
    }
}
