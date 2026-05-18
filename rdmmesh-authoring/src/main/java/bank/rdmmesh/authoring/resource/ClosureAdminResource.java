package bank.rdmmesh.authoring.resource;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import bank.rdmmesh.api.eventbus.ClosureRebuildDomainEvent;
import bank.rdmmesh.api.eventbus.EventBus;
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

    private static final Logger log = LoggerFactory.getLogger(ClosureAdminResource.class);

    private final AuthoringService authoring;
    private final EventBus eventBus;

    public ClosureAdminResource(AuthoringService authoring, EventBus eventBus) {
        this.authoring = authoring;
        this.eventBus = eventBus;
    }

    @POST
    @Path("/rebuild")
    @RolesAllowed("RDM_ADMIN")
    public ClosureRebuildResult rebuild(
            @Auth RdmmeshPrincipal principal,
            @PathParam("versionId") String versionId) {
        UUID id = parseUuid(versionId);
        ClosureRebuildResult result;
        try {
            result = authoring.rebuildClosure(id, principal.omUserId());
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(e.getMessage(), Response.Status.NOT_FOUND);
        }
        // E14 round 11 — event-coverage: admin-mutation closure-структуры
        // теперь попадает в audit-журнал (раньше только log.warn в сервисе).
        // Publish best-effort: сбой шины не должен ломать DR-операцию.
        try {
            eventBus.publish(new ClosureRebuildDomainEvent(
                    UUID.randomUUID(),
                    OffsetDateTime.now(ZoneOffset.UTC),
                    principal.omUserId(),
                    result.versionId(),
                    result.removed(),
                    result.inserted(),
                    result.total()));
        } catch (RuntimeException e) {
            log.warn("authoring: CLOSURE_REBUILD event publish failed (version_id={}): {}",
                    id, e.toString());
        }
        return result;
    }

    private static UUID parseUuid(String s) {
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException("versionId must be a UUID", Response.Status.BAD_REQUEST);
        }
    }
}
