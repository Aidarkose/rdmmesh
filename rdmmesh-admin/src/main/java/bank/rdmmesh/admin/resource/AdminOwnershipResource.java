package bank.rdmmesh.admin.resource;

import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.annotation.JsonProperty;

import bank.rdmmesh.admin.dto.AdminOwnershipView;
import bank.rdmmesh.admin.internal.AdminOwnershipService;
import bank.rdmmesh.api.security.RdmmeshPrincipal;
import io.dropwizard.auth.Auth;

/**
 * Per-row ownership операции (E18.4). Отдельный root @Path("/admin/ownership")
 * — НЕ collides с /admin/domains или /admin/codesets. Per-asset list+assign
 * живут внутри {@link AdminDomainResource}/{@link AdminCodeSetResource} (JAX-RS
 * root-resource matching выбирает наиболее специфичный класс-@Path; см. E17
 * прецедент DomainApproversResource).
 */
@Path("/admin/ownership")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("RDM_ADMIN")
public final class AdminOwnershipResource {

    private final AdminOwnershipService service;

    public AdminOwnershipResource(AdminOwnershipService service) {
        this.service = service;
    }

    @PATCH
    @Path("/{id}")
    public AdminOwnershipView patch(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @Valid @NotNull PatchOwnershipRequest req) {
        UUID uid = parseUuid(id, "id");
        if (req.pinnedLocal == null) {
            throw new WebApplicationException(
                    "Only pinned_local can be patched", Response.Status.BAD_REQUEST);
        }
        return service.setPinned(uid, req.pinnedLocal)
                .orElseThrow(() -> new NotFoundException("ownership " + id));
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@Auth RdmmeshPrincipal principal, @PathParam("id") String id) {
        UUID uid = parseUuid(id, "id");
        try {
            service.delete(uid);
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(e.getMessage());
        }
    }

    static UUID parseUuid(String s, String field) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new WebApplicationException(
                    field + " must be a UUID", Response.Status.BAD_REQUEST);
        }
    }

    public static final class PatchOwnershipRequest {
        @JsonProperty("pinned_local")
        public Boolean pinnedLocal;
    }

    /** Тело POST .../ownership (используется domain/codeset resource'ами). */
    public static final class AssignRequest {
        @JsonProperty("om_user_id")
        @jakarta.validation.constraints.NotEmpty
        public String omUserId;

        @JsonProperty("role")
        @jakarta.validation.constraints.NotEmpty
        public String role;

        @JsonProperty("pinned_local")
        public Boolean pinnedLocal;
    }
}
