package bank.rdmmesh.publishing.resource;

import java.util.Map;
import java.util.UUID;

import io.dropwizard.auth.Auth;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.publishing.internal.PublishingService;
import bank.rdmmesh.publishing.internal.PublishingService.PublishOutcome;

/**
 * Stage 7 (B): ручной повтор авто-публикации (RDM_ADMIN). Нужен, когда публикация
 * была отклонена пред-проверкой (PublishOutcome.BLOCKED) — версия осталась
 * OWNER_APPROVED. После починки данных (напр. FK-конфликта) админ повторяет
 * публикацию этим эндпоинтом; пред-проверка прогоняется заново.
 */
@Path("/versions/{id}/publish-retry")
@Produces(MediaType.APPLICATION_JSON)
public final class PublishRetryResource {

    private final PublishingService service;

    public PublishRetryResource(PublishingService service) {
        this.service = service;
    }

    @POST
    @RolesAllowed("RDM_ADMIN")
    public Response retry(@Auth RdmmeshPrincipal principal, @PathParam("id") String id) {
        UUID versionId;
        try {
            versionId = UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException("version not found: " + id);
        }
        PublishOutcome outcome = service.autoPublish(versionId, principal.omUserId());
        return Response.ok(Map.of("outcome", outcome.name())).build();
    }
}
