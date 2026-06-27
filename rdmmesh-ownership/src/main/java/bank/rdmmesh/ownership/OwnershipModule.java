package bank.rdmmesh.ownership;

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.port.ApproverDirectoryPort;
import bank.rdmmesh.api.port.CatalogMirrorPort;
import bank.rdmmesh.api.port.OwnershipPort;
import bank.rdmmesh.api.port.SigningKeyPort;
import bank.rdmmesh.ownership.internal.PostgresApproverDirectoryPort;
import bank.rdmmesh.ownership.internal.PostgresOwnershipPort;
import bank.rdmmesh.ownership.internal.om.CatalogSyncService;
import bank.rdmmesh.ownership.internal.om.OpenMetadataCatalogClient;
import bank.rdmmesh.ownership.internal.webhook.HmacVerifier;
import bank.rdmmesh.ownership.internal.webhook.OwnershipWebhookService;
import bank.rdmmesh.ownership.resource.CatalogSyncWebhookResource;
import bank.rdmmesh.ownership.resource.DomainApproversAdminResource;
import bank.rdmmesh.ownership.resource.DomainApproversResource;
import bank.rdmmesh.ownership.resource.DomainRoleDirectoryAdminResource;
import bank.rdmmesh.ownership.resource.OwnershipWebhookResource;

/**
 * Composition factory модуля {@code rdmmesh-ownership}. Экспортирует:
 * <ul>
 *   <li>{@link OwnershipPort} — для catalog (provisional owner) и workflow (asset roles);</li>
 *   <li>{@link OwnershipWebhookResource} — POST /webhooks/om/ownership (E7).</li>
 * </ul>
 */
public final class OwnershipModule {

    private OwnershipModule() {}

    public static OwnershipPort buildPort(Jdbi jdbi) {
        return new PostgresOwnershipPort(jdbi);
    }

    /**
     * Справочник ролей домена для адресной маршрутизации согласования
     * (BR-21/BR-22, handoff E17). Прокидывается в workflow (валидация
     * submit-assignee) и в {@link #buildDirectoryResource}.
     */
    public static ApproverDirectoryPort buildApproverDirectoryPort(Jdbi jdbi) {
        return new PostgresApproverDirectoryPort(jdbi);
    }

    /** REST: GET /domains/{id}/approvers (UI submit-диалог). */
    public static DomainApproversResource buildApproversResource(
            ApproverDirectoryPort directory) {
        return new DomainApproversResource(directory);
    }

    /** REST: POST /admin/domain-role-directory/reload (RDM_ADMIN, полная замена). */
    public static DomainRoleDirectoryAdminResource buildDirectoryAdminResource(
            ApproverDirectoryPort directory) {
        return new DomainRoleDirectoryAdminResource(directory);
    }

    /** REST: /admin/domains/{id}/approvers (RDM_ADMIN, адресно по domain_id). */
    public static DomainApproversAdminResource buildApproversAdminResource(
            ApproverDirectoryPort directory) {
        return new DomainApproversAdminResource(directory);
    }

    /**
     * Webhook resource принимает HMAC-подписанные ChangeEvent'ы из OM (SPEC §2.4) и
     * применяет их к {@code catalog.domain} либо {@code rdm_asset_ownership}.
     *
     * @param ownership должен быть {@link PostgresOwnershipPort} — внутренняя реализация
     *                  использует {@code computeDelta}, которого нет в публичном порту.
     */
    public static OwnershipWebhookResource buildWebhookResource(
            Jdbi jdbi,
            CatalogMirrorPort catalogMirror,
            OwnershipPort ownership,
            SigningKeyPort signingKey,
            EventBus eventBus,
            ObjectMapper json) {
        var hmac = new HmacVerifier(signingKey);
        var service = new OwnershipWebhookService(jdbi, catalogMirror, ownership, eventBus);
        return new OwnershipWebhookResource(hmac, service, json);
    }

    /**
     * Приёмник тонкого уведомления OM Alert {@code Domain_and_Roles_sync_for_RDMmesh}:
     * POST /webhooks/om/catalog-sync. По уведомлению RDM сам тянет домены и роли из
     * нативного OM REST API (Bearer bot-token) и апсертит в зеркало. Возвращает
     * {@link Optional#empty()}, если OM не сконфигурирован (нет baseUrl/botToken) —
     * тогда App просто не регистрирует ресурс.
     *
     * @param omWebhookKey тот же inbound-ключ HMAC, что у ownership-webhook'а
     *                     ({@code RDM_OM_WEBHOOK_HMAC_KEY}).
     */
    public static Optional<CatalogSyncWebhookResource> buildCatalogSyncResource(
            SigningKeyPort omWebhookKey,
            CatalogMirrorPort catalogMirror,
            String omBaseUrl,
            String omBotToken,
            Duration connectTimeout,
            Duration requestTimeout) {
        if (omBaseUrl == null || omBaseUrl.isBlank()
                || omBotToken == null || omBotToken.isBlank()) {
            return Optional.empty();
        }
        var client = new OpenMetadataCatalogClient(
                omBaseUrl, omBotToken, connectTimeout, requestTimeout);
        var service = new CatalogSyncService(client, catalogMirror);
        var hmac = new HmacVerifier(omWebhookKey);
        return Optional.of(new CatalogSyncWebhookResource(hmac, service));
    }
}
