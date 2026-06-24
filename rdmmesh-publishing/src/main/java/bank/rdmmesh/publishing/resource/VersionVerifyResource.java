package bank.rdmmesh.publishing.resource;

import java.util.UUID;

import io.dropwizard.auth.Auth;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.publishing.internal.PublishingService;
import bank.rdmmesh.publishing.internal.PublishingService.VerifyResult;

/**
 * SPEC §3.8 — verify-endpoint для published-версий. Любой аутентифицированный
 * потребитель может перепроверить целостность через recompute SHA-256 из текущих
 * items и сравнение с {@code content_hash} в {@code code_set_version}.
 */
@Path("/versions/{id}/verify")
@Produces(MediaType.APPLICATION_JSON)
public final class VersionVerifyResource {

    private final PublishingService service;

    public VersionVerifyResource(PublishingService service) {
        this.service = service;
    }

    @GET
    public VerifyResult verify(@Auth RdmmeshPrincipal principal, @PathParam("id") String id) {
        UUID versionId;
        try {
            versionId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("version not found: " + id);
        }
        try {
            return service.verify(versionId);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(e.getMessage());
        }
    }
}
