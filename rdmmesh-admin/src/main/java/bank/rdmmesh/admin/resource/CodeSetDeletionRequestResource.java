package bank.rdmmesh.admin.resource;

import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
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
 * Author submit (E22):
 * {@code POST /codesets/{codesetId}/deletion-requests}.
 *
 * <p>Это отдельный root resource, потому что JAX-RS 3.7.2 выбирает класс по
 * наиболее литеральному совпадению префикса. {@code CodeSetResource} с
 * {@code @Path("/codesets")} перебивает любой более общий root resource —
 * поэтому submit должен жить в классе с собственным {@code @Path}, который
 * как минимум сопоставим по литеральной длине ({@code "/codesets"} vs
 * {@code "/codesets/{codesetId}/deletion-requests"} — последний намного
 * специфичнее по литералам, выигрывает).
 */
@Path("/codesets/{codesetId}/deletion-requests")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public final class CodeSetDeletionRequestResource {

    private final AdminDeletionRequestService service;

    public CodeSetDeletionRequestResource(AdminDeletionRequestService service) {
        this.service = service;
    }

    @POST
    @RolesAllowed({"RDM_AUTHOR", "RDM_ADMIN"})
    public Response submit(
            @Auth RdmmeshPrincipal principal,
            @PathParam("codesetId") String codesetId,
            @Valid @NotNull SubmitRequest req) {
        UUID cs = DeletionRequestResource.parseUuid(codesetId, "codesetId");
        try {
            AdminDeletionRequestView v = service.submit(cs, req.reason, principal.omUserId());
            return Response.status(Response.Status.CREATED).entity(v).build();
        } catch (ValidationException e) {
            throw new WebApplicationException(e.getMessage(), 422);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.NOT_FOUND);
        } catch (IllegalStateException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        }
    }

    public static final class SubmitRequest {
        @JsonProperty("reason")
        @NotEmpty
        public String reason;
    }
}
