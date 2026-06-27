package bank.rdmmesh.ownership.resource;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.ownership.internal.om.CatalogSyncService;
import bank.rdmmesh.ownership.internal.om.CatalogSyncService.SyncResult;
import bank.rdmmesh.ownership.internal.webhook.HmacVerifier;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Приёмник тонкого уведомления OM Alert {@code Domain_and_Roles_sync_for_RDMmesh}.
 * OM Alert отфильтрован на типы объектов domain/role и шлёт сюда уведомление об
 * изменении; тело уведомления RDM <b>не доверяет и не парсит</b> — оно лишь триггер.
 * По уведомлению RDM сам обращается к нативному secure REST API OM (Bearer bot-token)
 * и выгружает домены и роли ({@link CatalogSyncService#resync()}).
 *
 * <p>Аутентификация уведомления: если OM присылает HMAC-подпись (заголовок
 * {@code X-OM-Event-Signature} или {@code X-OM-Signature}, формат {@code sha256=<hex>}),
 * она проверяется ключом {@code RDM_OM_WEBHOOK_HMAC_KEY}. Реальная граница безопасности —
 * сам pull по bot-token'у, поэтому при отсутствии подписи триггер логируется и
 * выполняется (pull идемпотентен). Endpoint не под JWT.
 */
@Path("/webhooks/om/catalog-sync")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public final class CatalogSyncWebhookResource {

    private static final Logger log = LoggerFactory.getLogger(CatalogSyncWebhookResource.class);

    private final HmacVerifier hmac;
    private final CatalogSyncService sync;

    public CatalogSyncWebhookResource(HmacVerifier hmac, CatalogSyncService sync) {
        this.hmac = hmac;
        this.sync = sync;
    }

    @POST
    public Response receive(
            @HeaderParam("X-OM-Event-Signature") String omSig,
            @HeaderParam("X-OM-Signature") String altSig,
            byte[] rawBody) {
        String sig = omSig != null ? omSig : altSig;
        if (sig != null) {
            if (rawBody == null || !hmac.verify(sig, rawBody)) {
                log.warn("catalog-sync: HMAC mismatch — отклонено");
                return Response.status(Response.Status.UNAUTHORIZED)
                        .entity(Map.of("error", "invalid signature")).build();
            }
        } else {
            log.warn("catalog-sync: уведомление без подписи — триггерим pull "
                    + "(secure-граница — bot-token pull)");
        }

        SyncResult r = sync.resync();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("outcome", "SYNCED");
        body.put("domains_synced", r.domainsSynced());
        body.put("domains_skipped", r.domainsSkipped());
        body.put("roles_synced", r.rolesSynced());
        body.put("roles_skipped", r.rolesSkipped());
        return Response.ok(body).build();
    }
}
