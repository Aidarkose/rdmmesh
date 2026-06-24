package bank.rdmmesh.publishing.resource;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;

import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.publishing.internal.outbound.SubscriptionService;
import bank.rdmmesh.publishing.internal.outbound.dao.SubscriptionDao.SubscriptionRow;
import io.dropwizard.auth.Auth;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * REST CRUD над outbound webhook subscriptions (SPEC §3.5):
 *
 * <pre>
 *   GET    /api/v1/subscriptions          — список (RDM_ADMIN)
 *   GET    /api/v1/subscriptions/{id}     — одна (RDM_ADMIN)
 *   POST   /api/v1/subscriptions          — создать (RDM_ADMIN)
 *   DELETE /api/v1/subscriptions/{id}     — деактивировать (RDM_ADMIN)
 * </pre>
 *
 * <p>На пилоте subscriptions — global resource (RDM_ADMIN). Domain-scoped management
 * (admin'ы своего домена) — V1+. SPEC §3.5 не требует domain-RBAC на E9.
 *
 * <p>«Удаление» — soft (active=false). Оставляем строки в БД, чтобы outbox-rows и
 * аудит на эту subscription не теряли FK; повторно создать subscription с тем же
 * {@code (url, secret_id, filter)} никто не запрещает.
 */
@Path("/subscriptions")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("RDM_ADMIN")
public final class SubscriptionResource {

    private final SubscriptionService service;
    private final ObjectMapper json;

    public SubscriptionResource(SubscriptionService service, ObjectMapper json) {
        this.service = service;
        this.json = json;
    }

    @GET
    public List<SubscriptionDto.View> list(@Auth RdmmeshPrincipal principal) {
        return service.list().stream().map(r -> SubscriptionDto.View.from(r, json)).toList();
    }

    @GET
    @Path("/{id}")
    public SubscriptionDto.View get(@Auth RdmmeshPrincipal principal, @PathParam("id") UUID id) {
        return service.findById(id)
                .map(r -> SubscriptionDto.View.from(r, json))
                .orElseThrow(() -> new WebApplicationException(
                        "subscription " + id + " не найдена", Response.Status.NOT_FOUND));
    }

    @POST
    public Response create(@Auth RdmmeshPrincipal principal, SubscriptionDto.CreateRequest req) {
        if (req == null) {
            throw new WebApplicationException("body is required", Response.Status.BAD_REQUEST);
        }
        try {
            SubscriptionRow row = service.create(
                    req.url(), req.secretId(), req.filter(),
                    req.active() == null || req.active(),
                    principal.omUserId());
            return Response.status(Response.Status.CREATED)
                    .entity(SubscriptionDto.View.from(row, json))
                    .build();
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@Auth RdmmeshPrincipal principal, @PathParam("id") UUID id) {
        Optional<SubscriptionRow> existing = service.findById(id);
        if (existing.isEmpty()) {
            throw new WebApplicationException(
                    "subscription " + id + " не найдена", Response.Status.NOT_FOUND);
        }
        service.deactivate(id);
        return Response.noContent().build();
    }
}
