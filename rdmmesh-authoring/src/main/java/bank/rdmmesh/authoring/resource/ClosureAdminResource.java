package bank.rdmmesh.authoring.resource;

import java.util.UUID;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.authoring.internal.service.AuthoringService;
import bank.rdmmesh.authoring.internal.service.AuthoringService.ClosureRebuildResult;
import io.dropwizard.auth.Auth;

/**
 * Admin-only disaster-recovery API для closure-table иерархий.
 *
 * <p>В нормальной работе обслуживание {@code authoring.code_item_closure}
 * выполняется триггерами V022 (incremental update на каждый INSERT/DELETE/move).
 * Этот endpoint существует для случаев, когда closure разошлась с code_item —
 * после ручного SQL, инцидента на стороне БД либо WARN'а из V023 sanity check.
 *
 * <p>{@code TRUNCATE+rebuild} одной версии — это {@code DELETE} closure-rows для
 * versionId + повторный {@code WITH RECURSIVE} walk через actual code_item.
 * Триггеры V022/V023 на code_item не дёргаются (мы не трогаем code_item),
 * cycle-invariant trigger тоже не — он смотрит operations на code_item, а не
 * на closure.
 *
 * <p>Authorization: {@code RDM_ADMIN} — операция админская, без asset-level
 * detalization. Asset-level админ-роли (например, RDM_DOMAIN_ADMIN) — V1+.
 */
@Path("/versions/{versionId}/closure")
@Produces(MediaType.APPLICATION_JSON)
public final class ClosureAdminResource {

    private final AuthoringService authoring;

    public ClosureAdminResource(AuthoringService authoring) {
        this.authoring = authoring;
    }

    @POST
    @Path("/rebuild")
    @RolesAllowed("RDM_ADMIN")
    public ClosureRebuildResult rebuild(
            @Auth RdmmeshPrincipal principal,
            @PathParam("versionId") String versionId) {
        UUID id = parseUuid(versionId);
        try {
            return authoring.rebuildClosure(id, principal.omUserId());
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.NOT_FOUND);
        }
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("versionId must be a UUID", Response.Status.BAD_REQUEST);
        }
    }
}
