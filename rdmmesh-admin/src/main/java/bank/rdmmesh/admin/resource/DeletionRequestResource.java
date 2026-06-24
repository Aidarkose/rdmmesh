package bank.rdmmesh.admin.resource;

import java.util.List;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import bank.rdmmesh.admin.dto.AdminDeletionRequestView;
import bank.rdmmesh.admin.internal.AdminDeletionRequestService;
import bank.rdmmesh.admin.internal.AdminDeletionRequestService.ForbiddenException;
import bank.rdmmesh.api.security.RdmmeshPrincipal;
import io.dropwizard.auth.Auth;

/**
 * Author-facing endpoints под {@code /deletion-requests/...} (E22):
 *
 * <ul>
 *   <li>{@code GET  /deletion-requests/my} — мои заявки (любой статус)</li>
 *   <li>{@code POST /deletion-requests/{id}:cancel} — Author отзывает свою PENDING</li>
 * </ul>
 *
 * <p>Submit (на codeset'е) живёт в отдельном root resource'е
 * {@link CodeSetDeletionRequestResource}: в JAX-RS routing'е выигрывает класс с
 * наиболее литеральным @Path, и {@code CodeSetResource @Path("/codesets")} перекрывает
 * любой более общий root resource. Поэтому submit-метод нужно держать в классе с
 * собственным {@code @Path("/codesets/{codesetId}/deletion-requests")}.
 */
@Path("/deletion-requests")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public final class DeletionRequestResource {

    private final AdminDeletionRequestService service;

    public DeletionRequestResource(AdminDeletionRequestService service) {
        this.service = service;
    }

    @GET
    @Path("/my")
    @RolesAllowed({"RDM_AUTHOR", "RDM_ADMIN"})
    public List<AdminDeletionRequestView> listMy(@Auth RdmmeshPrincipal principal) {
        return service.listMy(principal.omUserId());
    }

    @POST
    @Path("/{id}:cancel")
    @RolesAllowed({"RDM_AUTHOR", "RDM_ADMIN"})
    public Response cancel(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id) {
        UUID rid = parseUuid(id, "id");
        try {
            service.cancel(rid, principal.omUserId());
            return Response.noContent().build();
        } catch (ForbiddenException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.FORBIDDEN);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.NOT_FOUND);
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
    }

    static UUID parseUuid(String s, String field) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new WebApplicationException(field + " must be a UUID", Response.Status.BAD_REQUEST);
        }
    }
}
