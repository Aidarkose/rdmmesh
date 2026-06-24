package bank.rdmmesh.ownership.resource;

import java.util.List;
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

import bank.rdmmesh.api.port.ApproverDirectoryPort;
import bank.rdmmesh.api.port.ApproverDirectoryPort.Approver;
import bank.rdmmesh.api.security.RdmmeshPrincipal;
import io.dropwizard.auth.Auth;

/**
 * {@code /admin/domains/{domainId}/approvers} (RDM_ADMIN) — адресное управление
 * согласующими конкретного домена по {@code domain_id} (источник
 * {@code RDM_ADMIN_LOCAL}). В отличие от {@code /admin/domain-role-directory/reload}
 * (глобальный TRUNCATE+INSERT по {@code om_domain_id}), работает с одним доменом и
 * не требует связи с OpenMetadata — подходит для локальных доменов.
 *
 * <p>Кандидаты, добавленные тут, видны в submit-диалоге через
 * {@code GET /domains/{domainId}/approvers}.
 */
@Path("/admin/domains/{domainId}/approvers")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public final class DomainApproversAdminResource {

    private final ApproverDirectoryPort directory;

    public DomainApproversAdminResource(ApproverDirectoryPort directory) {
        this.directory = directory;
    }

    @GET
    @RolesAllowed("RDM_ADMIN")
    public List<Approver> list(
            @Auth RdmmeshPrincipal principal,
            @PathParam("domainId") String domainId) {
        return directory.approversOf(parseUuid(domainId, "domainId"), null);
    }

    @POST
    @RolesAllowed("RDM_ADMIN")
    public Response add(
            @Auth RdmmeshPrincipal principal,
            @PathParam("domainId") String domainId,
            @NotNull AddApproverRequest req) {
        UUID dom = parseUuid(domainId, "domainId");
        String role = requireRole(req.role);
        UUID user = parseUuid(req.omUserId, "om_user_id");
        directory.addLocal(dom, role, user, req.username, req.displayName);
        return Response.ok(directory.approversOf(dom, null)).build();
    }

    @DELETE
    @RolesAllowed("RDM_ADMIN")
    public Response remove(
            @Auth RdmmeshPrincipal principal,
            @PathParam("domainId") String domainId,
            @QueryParam("role") String role,
            @QueryParam("om_user_id") String omUserId) {
        UUID dom = parseUuid(domainId, "domainId");
        boolean removed = directory.removeLocal(dom, requireRole(role), parseUuid(omUserId, "om_user_id"));
        if (!removed) {
            throw new WebApplicationException("approver not found", Response.Status.NOT_FOUND);
        }
        return Response.noContent().build();
    }

    private static String requireRole(String role) {
        if (!ApproverDirectoryPort.STEWARD.equals(role)
                && !ApproverDirectoryPort.BUSINESS_OWNER.equals(role)) {
            throw new WebApplicationException(
                    "role must be STEWARD or BUSINESS_OWNER", Response.Status.BAD_REQUEST);
        }
        return role;
    }

    private static UUID parseUuid(String s, String field) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new WebApplicationException(
                    field + " must be a UUID", Response.Status.BAD_REQUEST);
        }
    }

    /** Тело POST: добавить одного согласующего домену. */
    public static final class AddApproverRequest {
        @JsonProperty("role")
        public String role;

        @JsonProperty("om_user_id")
        public String omUserId;

        @JsonProperty("username")
        public String username;

        @JsonProperty("display_name")
        public String displayName;
    }
}
