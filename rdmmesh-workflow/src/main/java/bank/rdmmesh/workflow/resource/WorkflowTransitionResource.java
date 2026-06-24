package bank.rdmmesh.workflow.resource;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.validation.Valid;
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

import bank.rdmmesh.api.port.WorkflowPort;
import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.spec.api.TransitionRequest;
import bank.rdmmesh.spec.events.WorkflowTransitionEvent;
import bank.rdmmesh.workflow.internal.engine.WorkflowEngine;
import bank.rdmmesh.workflow.internal.service.SubmitAssigneeHolder;
import bank.rdmmesh.workflow.internal.service.WorkflowService;
import io.dropwizard.auth.Auth;

/**
 * REST-обвязка workflow:
 * <ul>
 *   <li>{@code POST /versions/{id}/transitions} — выполнить transition;</li>
 *   <li>{@code GET  /versions/{id}/history} — журнал переходов версии.</li>
 * </ul>
 *
 * <p>Authorization gate в {@code @RolesAllowed} здесь — лишь грубый периметр
 * («любой не-Consumer»). Точная логика role-gate (steward vs owner, asset vs
 * base-role) живёт в {@link bank.rdmmesh.workflow.internal.StateMachine} и
 * вычисляется поверх {@code OwnershipPort.rolesOf(...)} плюс
 * {@code RdmmeshPrincipal.baseRoles()}.
 */
@Path("/versions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public final class WorkflowTransitionResource {

    private final WorkflowEngine engine;
    private final WorkflowService service;

    public WorkflowTransitionResource(WorkflowEngine engine, WorkflowService service) {
        this.engine = engine;
        this.service = service;
    }

    @POST
    @Path("/{versionId}/transitions")
    @RolesAllowed({"RDM_AUTHOR", "RDM_STEWARD", "RDM_OWNER", "RDM_ADMIN"})
    public Response transition(
            @Auth RdmmeshPrincipal principal,
            @PathParam("versionId") String versionId,
            @Valid TransitionRequest req) {
        if (req == null || req.getTo() == null) {
            throw new WebApplicationException("body.to is required", Response.Status.BAD_REQUEST);
        }
        UUID id = parseUuid(versionId, "versionId");

        // E17 / BR-21: на submit (DRAFT → IN_REVIEW) Author обязан выбрать
        // согласующих — домен + steward-учётку + business-owner-учётку.
        // Продуктовое правило enforce'ится здесь, на REST-границе;
        // WorkflowService остаётся толерантным (assignee опционален —
        // обратная совместимость для ITs, вызывающих service напрямую).
        boolean isSubmit = "IN_REVIEW".equals(req.getTo().value());
        SubmitAssigneeHolder.Assignee assignee = null;
        if (isSubmit) {
            assignee = parseAssignee(req);
        }
        try {
            if (assignee != null) {
                SubmitAssigneeHolder.set(assignee);
            }
            WorkflowTransitionEvent ev = engine.transition(
                    id,
                    req.getTo().value(),
                    principal.omUserId(),
                    Set.copyOf(principal.baseRoles()),
                    req.getComment());
            // expected_status — мягкая проверка: если клиент явно сообщил, что
            // ожидал version в каком-то статусе, и он не совпал, серверный CAS уже
            // отдал бы IllegalStateTransitionException. Дополнительной проверки
            // здесь нет — это не optimistic-lock на ID, а информационный hint.
            return Response.ok(ev).build();
        } catch (IllegalArgumentException e) {
            // Unknown version → 404; всё остальное — 400.
            String msg = e.getMessage();
            if (msg != null && msg.startsWith("Unknown version")) {
                throw new NotFoundException(msg);
            }
            throw new WebApplicationException(msg, Response.Status.BAD_REQUEST);
        } catch (WorkflowPort.SelfApprovalException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        } catch (WorkflowPort.InsufficientRoleException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.FORBIDDEN);
        } catch (WorkflowPort.IllegalStateTransitionException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.CONFLICT);
        } finally {
            SubmitAssigneeHolder.clear();
        }
    }

    /**
     * BR-21: assignee обязателен на submit. Все три поля
     * (domain_id / steward_om_user_id / owner_om_user_id) — UUID, иначе 400.
     */
    private static SubmitAssigneeHolder.Assignee parseAssignee(TransitionRequest req) {
        var a = req.getAssignee();
        if (a == null
                || a.getDomainId() == null
                || a.getStewardOmUserId() == null
                || a.getOwnerOmUserId() == null) {
            throw new WebApplicationException(
                    "submit требует assignee: domain_id + steward_om_user_id"
                            + " + owner_om_user_id",
                    Response.Status.BAD_REQUEST);
        }
        return new SubmitAssigneeHolder.Assignee(
                parseUuid(a.getDomainId(), "assignee.domain_id"),
                parseUuid(a.getStewardOmUserId(), "assignee.steward_om_user_id"),
                parseUuid(a.getOwnerOmUserId(), "assignee.owner_om_user_id"));
    }

    @GET
    @Path("/{versionId}/history")
    public List<WorkflowTransitionEvent> history(
            @Auth RdmmeshPrincipal principal,
            @PathParam("versionId") String versionId) {
        UUID id = parseUuid(versionId, "versionId");
        try {
            return service.history(id);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(e.getMessage());
        }
    }

    private static UUID parseUuid(String s, String field) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(field + " must be a UUID", Response.Status.BAD_REQUEST);
        }
    }
}
