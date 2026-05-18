package bank.rdmmesh.publishing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jdbi.v3.core.Jdbi;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.api.port.OutboundPort;
import bank.rdmmesh.api.port.PublishedSnapshotPort;
import bank.rdmmesh.api.port.SigningKeyPort;
import bank.rdmmesh.api.port.VersionLifecyclePort;
import bank.rdmmesh.api.port.WebhookKeyPort;
import bank.rdmmesh.api.port.WorkflowJournalPort;
import bank.rdmmesh.publishing.internal.HmacSigner;
import bank.rdmmesh.publishing.internal.PublishingService;
import bank.rdmmesh.publishing.internal.egress.EgressPolicy;
import bank.rdmmesh.publishing.internal.outbound.OutboxOutboundAdapter;
import bank.rdmmesh.publishing.internal.outbound.SubscriptionService;
import bank.rdmmesh.publishing.internal.outbound.WebhookDeliveryWorker;
import bank.rdmmesh.publishing.resource.SubscriptionResource;
import bank.rdmmesh.publishing.resource.VersionVerifyResource;

/**
 * Composition factory для {@code rdmmesh-publishing}. Связывает PublishingService с
 * шиной событий (auto-publish после OWNER_APPROVED), собирает REST verify-endpoint,
 * outbound адаптер и subscription-resource (E9).
 */
public final class PublishingModule {

    private PublishingModule() {}

    /** По умолчанию tick worker'а — 5 секунд. Переопределяется конфигом в проде. */
    public static final long DEFAULT_DELIVERY_TICK_SECONDS = 5L;

    public static Resources build(
            Jdbi jdbi,
            VersionLifecyclePort lifecycle,
            PublishedSnapshotPort snapshots,
            CatalogReadPort catalog,
            WorkflowJournalPort journal,
            SigningKeyPort signingKey,
            WebhookKeyPort webhookKey,
            EventBus eventBus,
            ObjectMapper json) {
        return build(jdbi, lifecycle, snapshots, catalog, journal,
                signingKey, webhookKey, eventBus, json, DEFAULT_DELIVERY_TICK_SECONDS);
    }

    public static Resources build(
            Jdbi jdbi,
            VersionLifecyclePort lifecycle,
            PublishedSnapshotPort snapshots,
            CatalogReadPort catalog,
            WorkflowJournalPort journal,
            SigningKeyPort signingKey,
            WebhookKeyPort webhookKey,
            EventBus eventBus,
            ObjectMapper json,
            long deliveryTickSeconds) {
        HmacSigner signer = new HmacSigner(signingKey);
        OutboundPort outbound = new OutboxOutboundAdapter(jdbi, webhookKey, json);
        PublishingService service = new PublishingService(
                jdbi, lifecycle, snapshots, catalog, journal, signer, outbound, eventBus);
        service.registerOn(eventBus);
        // F4 SSRF-guard (E14 round 12): safe-by-default egress-политика
        // (loopback/link-local/metadata всегда deny; приватные сети — только
        // если в RDM_WEBHOOK_EGRESS_PRIVATE_ALLOWLIST, пусто по умолчанию).
        EgressPolicy egress = EgressPolicy.fromEnv();
        SubscriptionService subscriptions = new SubscriptionService(jdbi, json, egress);
        WebhookDeliveryWorker worker =
                new WebhookDeliveryWorker(jdbi, deliveryTickSeconds, egress);
        return new Resources(
                service,
                outbound,
                worker,
                new VersionVerifyResource(service),
                new SubscriptionResource(subscriptions, json));
    }

    public record Resources(
            PublishingService service,
            OutboundPort outbound,
            WebhookDeliveryWorker deliveryWorker,
            VersionVerifyResource verify,
            SubscriptionResource subscriptions) {}
}
