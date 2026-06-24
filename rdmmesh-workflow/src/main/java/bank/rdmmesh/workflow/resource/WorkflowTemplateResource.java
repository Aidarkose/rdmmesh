package bank.rdmmesh.workflow.resource;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.workflow.internal.dao.WorkflowTemplateDao.TemplateRow;
import bank.rdmmesh.workflow.internal.engine.WorkflowTemplateService;
import bank.rdmmesh.workflow.internal.engine.WorkflowTemplateService.DeployResult;
import io.dropwizard.auth.Auth;

/**
 * REST управления per-domain BPMN-шаблонами (V2 / BR-18 round 2).
 * Только {@code RDM_ADMIN} — топология workflow домена меняется
 * администратором (SPEC §2.1: RDM Admin управляет шаблонами workflow).
 * Регистрируется лишь когда {@code RDM_WORKFLOW_ENGINE=flowable}.
 *
 * <p><b>No-bypass сохранён (модель A, ADR-0009).</b> Даже кастомный BPMN
 * приводит каждый переход через {@code WorkflowService}+enum-StateMachine —
 * админ не может топологией обойти 4-eyes/self-approval. Контракт BPMN
 * (rt_await + delegate) валидируется при деплое (невалид → 400).
 */
@Path("/workflow")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("RDM_ADMIN")
public final class WorkflowTemplateResource {

    private final WorkflowTemplateService service;

    public WorkflowTemplateResource(WorkflowTemplateService service) {
        this.service = service;
    }

    @POST
    @Path("/domains/{domainId}/template")
    @Consumes({MediaType.APPLICATION_XML, "text/xml", MediaType.APPLICATION_OCTET_STREAM})
    public Response deploy(
            @Auth RdmmeshPrincipal principal,
            @PathParam("domainId") String domainId,
            byte[] bpmnXml) {
        UUID dom = parseUuid(domainId);
        try {
            DeployResult r = service.deploy(dom, bpmnXml, principal.omUserId());
            return Response.status(Response.Status.CREATED).entity(r).build();
        } catch (IllegalArgumentException e) {
            // Контракт BPMN не выполнен / не распарсился.
            throw new WebApplicationException(e.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    @GET
    @Path("/domains/{domainId}/template")
    public TemplateRow active(@PathParam("domainId") String domainId) {
        UUID dom = parseUuid(domainId);
        return service.active(dom).orElseThrow(() ->
                new NotFoundException("нет активного шаблона для домена " + dom
                        + " (действует дефолтный rdm4eyes)"));
    }

    @GET
    @Path("/templates")
    public List<TemplateRow> list() {
        return service.listAll();
    }

    @DELETE
    @Path("/domains/{domainId}/template")
    public Map<String, Object> revert(@PathParam("domainId") String domainId) {
        UUID dom = parseUuid(domainId);
        boolean reverted = service.revertToDefault(dom);
        return Map.of("domain_id", dom.toString(), "reverted", reverted,
                "effective", "rdm4eyes (default)");
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(
                    "domainId must be a UUID", Response.Status.BAD_REQUEST);
        }
    }
}
