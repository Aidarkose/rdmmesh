package bank.rdmmesh.admin.resource;

import java.util.List;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.annotation.JsonProperty;

import bank.rdmmesh.admin.dto.AdminTaskView;
import bank.rdmmesh.admin.internal.AdminTaskService;
import bank.rdmmesh.api.security.RdmmeshPrincipal;
import io.dropwizard.auth.Auth;

/**
 * GET /admin/tasks/my   — список PENDING admin-задач (E18.6).
 * POST /admin/tasks/{id}:resolve — резолв задачи.
 *
 * <p>До E18.3 (webhook receiver upgrade) PENDING-задачи никем не создаются,
 * поэтому /my возвращает пустой массив — UI рендерит секцию-плейсхолдер.
 */
@Path("/admin/tasks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed("RDM_ADMIN")
public final class AdminTaskResource {

    private final AdminTaskService service;

    public AdminTaskResource(AdminTaskService service) {
        this.service = service;
    }

    @GET
    @Path("/my")
    public List<AdminTaskView> myTasks(@Auth RdmmeshPrincipal principal) {
        return service.listPending();
    }

    @POST
    @Path("/{id}:resolve")
    public Response resolve(
            @Auth RdmmeshPrincipal principal,
            @PathParam("id") String id,
            @Valid @NotNull ResolveRequest req) {
        UUID uid = parseUuid(id, "id");
        try {
            service.resolve(uid, req.action, req.notes, principal.omUserId());
            return Response.noContent().build();
        } catch (IllegalArgumentException e) {
            // Не различаем 404 и 400 по тексту — попадание сюда либо «задача не нашлась»,
            // либо «недопустимый action». UI получит 400 в обоих случаях.
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
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

    public static final class ResolveRequest {
        @JsonProperty("action")
        @NotEmpty
        public String action;

        @JsonProperty("notes")
        public String notes;
    }
}
