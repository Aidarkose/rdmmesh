package bank.rdmmesh.ownership.resource;

import java.util.List;
import java.util.UUID;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import bank.rdmmesh.api.port.ApproverDirectoryPort;
import bank.rdmmesh.api.port.ApproverDirectoryPort.Approver;
import bank.rdmmesh.api.security.RdmmeshPrincipal;
import io.dropwizard.auth.Auth;

/**
 * {@code GET /domains/{domainId}/approvers?role=STEWARD|BUSINESS_OWNER}
 * (BR-21, SPEC §3.5, handoff E17) — кандидаты-согласующие домена из
 * справочника ролей домена, для UI submit-диалога.
 *
 * <p>Отдельный root-resource с {@code @Path("/domains/{domainId}/approvers")}
 * (не подметод catalog-{@code DomainResource}, который в другом модуле):
 * Jersey матчит более специфичный root-resource для этого URL, а доменные
 * данные ролей принадлежат модулю {@code ownership}.
 */
@Path("/domains/{domainId}/approvers")
@Produces(MediaType.APPLICATION_JSON)
public final class DomainApproversResource {

    private final ApproverDirectoryPort directory;

    public DomainApproversResource(ApproverDirectoryPort directory) {
        this.directory = directory;
    }

    @GET
    public List<Approver> approvers(
            @Auth RdmmeshPrincipal principal,
            @PathParam("domainId") String domainId,
            @QueryParam("role") String role) {
        UUID dom;
        try {
            dom = UUID.fromString(domainId);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(
                    "domainId must be a UUID", Response.Status.BAD_REQUEST);
        }
        if (role != null
                && !role.equals(ApproverDirectoryPort.STEWARD)
                && !role.equals(ApproverDirectoryPort.BUSINESS_OWNER)) {
            throw new WebApplicationException(
                    "role must be STEWARD or BUSINESS_OWNER", Response.Status.BAD_REQUEST);
        }
        return directory.approversOf(dom, role);
    }
}
