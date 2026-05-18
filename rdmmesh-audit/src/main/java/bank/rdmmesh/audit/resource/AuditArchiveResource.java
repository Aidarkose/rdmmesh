package bank.rdmmesh.audit.resource;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.audit.internal.AuditArchiveService;
import bank.rdmmesh.audit.internal.AuditArchiveService.Result;
import bank.rdmmesh.audit.internal.AuditArchiveService.VerifyResult;
import io.dropwizard.auth.Auth;

/**
 * E14 round 10 — ops-endpoint заливки месячного сегмента {@code audit_log} в
 * immutable-store (RustFS/S3) с записью в {@code audit.archive_manifest}.
 *
 * <pre>
 *   POST /api/v1/audit/archive?year=2026&amp;month=5     @RolesAllowed("RDM_ADMIN")
 * </pre>
 *
 * <p>Только {@code RDM_ADMIN} (RDM_AUDITOR — read-only, архивация — мутация
 * ops-flow'а). После успешного ответа сегмент можно дропать через
 * {@code SELECT audit.drop_audit_partition_if_archived('<segment_label>')}
 * (1-арг overload V074 выводит archived из манифеста, не honor-system).
 */
@Path("/audit/archive")
@Produces(MediaType.APPLICATION_JSON)
public final class AuditArchiveResource {

    private final AuditArchiveService service;

    public AuditArchiveResource(AuditArchiveService service) {
        this.service = service;
    }

    @POST
    @RolesAllowed("RDM_ADMIN")
    public Result archive(
            @Auth RdmmeshPrincipal principal,
            @QueryParam("year") Integer year,
            @QueryParam("month") Integer month) {
        if (year == null || month == null || month < 1 || month > 12) {
            throw new WebApplicationException(
                    "year и month (1..12) обязательны", Response.Status.BAD_REQUEST);
        }
        try {
            return service.archiveMonth(year, month, principal.omUserId());
        } catch (IllegalStateException disabled) {
            // ArchivePort не сконфигурирован (RDM_ARCHIVE_ENDPOINT пуст).
            throw new WebApplicationException(
                    disabled.getMessage(), Response.Status.SERVICE_UNAVAILABLE);
        } catch (IllegalArgumentException empty) {
            throw new WebApplicationException(
                    empty.getMessage(), Response.Status.BAD_REQUEST);
        }
    }

    /**
     * Independent-verify заархивированного сегмента (E14.11 §3 #5): скачать
     * объект из immutable-store, пересчитать SHA-256, сверить с
     * {@code audit.archive_manifest}. Read-only → RDM_AUDITOR допустим.
     *
     * <pre>GET /api/v1/audit/archive/{segment}/verify</pre>
     */
    @GET
    @Path("/{segment}/verify")
    @RolesAllowed({"RDM_ADMIN", "RDM_AUDITOR"})
    public VerifyResult verify(
            @Auth RdmmeshPrincipal principal,
            @PathParam("segment") String segment) {
        try {
            return service.verifySegment(segment);
        } catch (IllegalStateException disabled) {
            throw new WebApplicationException(
                    disabled.getMessage(), Response.Status.SERVICE_UNAVAILABLE);
        } catch (IllegalArgumentException notArchived) {
            throw new WebApplicationException(
                    notArchived.getMessage(), Response.Status.NOT_FOUND);
        }
    }
}
