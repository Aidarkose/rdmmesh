package bank.rdmmesh.workflow.resource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.workflow.internal.dao.ApprovalTaskDao.ApprovalTaskRow;
import bank.rdmmesh.workflow.internal.service.WorkflowService;
import io.dropwizard.auth.Auth;

/**
 * GET /tasks/my — список открытых approval-задач, в которых текущий пользователь
 * присутствует в {@code candidate_users}. Список заполняется {@code WorkflowService}
 * по {@code OwnershipPort.ownersOf(codesetId, "CODESET")} в момент перехода —
 * до E7 (OM ownership webhook) реальных STEWARD'ов в БД нет, и видимость
 * "/tasks/my" соответственно ограниченная (provisional OWNER на собственных
 * CodeSet'ах, и — после steward_approve — следующая OWNER-задача).
 */
@Path("/tasks/my")
@Produces(MediaType.APPLICATION_JSON)
public final class MyTasksResource {

    private final WorkflowService service;

    public MyTasksResource(WorkflowService service) {
        this.service = service;
    }

    @GET
    public List<ApprovalTaskDto> myTasks(@Auth RdmmeshPrincipal principal) {
        return service.openTasksFor(principal.omUserId()).stream()
                .map(MyTasksResource::toDto)
                .collect(Collectors.toList());
    }

    private static ApprovalTaskDto toDto(ApprovalTaskRow row) {
        return new ApprovalTaskDto(
                row.id(),
                row.versionId(),
                row.codesetId(),
                row.domainId(),
                row.requiredRole(),
                row.candidateUsers() == null ? List.of() : List.of(row.candidateUsers()),
                OffsetDateTime.ofInstant(row.createdAt(), ZoneOffset.UTC));
    }

    public record ApprovalTaskDto(
            UUID id,
            UUID versionId,
            UUID codesetId,
            UUID domainId,
            String requiredRole,
            List<UUID> candidateUsers,
            OffsetDateTime createdAt) {}
}
