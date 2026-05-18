package bank.rdmmesh.ownership;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.port.CatalogMirrorPort;
import bank.rdmmesh.api.port.OwnershipPort;
import bank.rdmmesh.api.port.SigningKeyPort;
import bank.rdmmesh.ownership.internal.PostgresOwnershipPort;
import bank.rdmmesh.ownership.internal.webhook.HmacVerifier;
import bank.rdmmesh.ownership.internal.webhook.OwnershipWebhookService;
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
}
