package bank.rdmmesh.app;

import org.flywaydb.core.Flyway;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.postgres.PostgresPlugin;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.port.ArchivePort;
import bank.rdmmesh.api.port.CatalogMirrorPort;
import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.api.port.IdentityPort;
import bank.rdmmesh.api.port.OwnershipPort;
import bank.rdmmesh.api.port.PublishedSnapshotPort;
import bank.rdmmesh.api.port.SigningKeyPort;
import bank.rdmmesh.api.port.VersionLifecyclePort;
import bank.rdmmesh.api.port.WebhookKeyPort;
import bank.rdmmesh.api.port.WorkflowJournalPort;
import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.app.archive.ArchiveAdapters;
import bank.rdmmesh.app.auth.AuthResource;
import bank.rdmmesh.app.eventbus.SyncEventBus;
import bank.rdmmesh.app.health.InfoHealthCheck;
import bank.rdmmesh.app.security.EnvSigningKeyAdapter;
import bank.rdmmesh.app.security.EnvWebhookKeyAdapter;
import bank.rdmmesh.audit.AuditModule;
import bank.rdmmesh.authoring.AuthoringModule;
import bank.rdmmesh.catalog.CatalogModule;
import bank.rdmmesh.distribution.DistributionModule;
import bank.rdmmesh.identity.IdentityModule;
import bank.rdmmesh.identity.JwtAuthenticator;
import bank.rdmmesh.identity.RoleAuthorizer;
import bank.rdmmesh.ownership.OwnershipModule;
import bank.rdmmesh.publishing.PublishingModule;
import bank.rdmmesh.workflow.WorkflowModule;
import io.dropwizard.auth.AuthDynamicFeature;
import io.dropwizard.auth.AuthValueFactoryProvider;
import io.dropwizard.auth.oauth.OAuthCredentialAuthFilter;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.jdbi3.JdbiFactory;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;

/**
 * rdmmesh composition root. The only place that knows about all bounded contexts;
 * each module exposes its public API via interfaces from {@code rdmmesh-api}.
 *
 * <p>MVP wiring (resources, services, ports) lands here as modules get filled in.
 */
public final class RdmmeshApplication extends Application<RdmmeshConfiguration> {

    private static final Logger log = LoggerFactory.getLogger(RdmmeshApplication.class);

    public static void main(String[] args) throws Exception {
        new RdmmeshApplication().run(args);
    }

    @Override
    public String getName() {
        return "rdmmesh";
    }

    @Override
    public void initialize(Bootstrap<RdmmeshConfiguration> bootstrap) {
        bootstrap.setConfigurationSourceProvider(new SubstitutingSourceProvider(
                bootstrap.getConfigurationSourceProvider(),
                new EnvironmentVariableSubstitutor(/* strict */ false)));
    }

    @Override
    public void run(RdmmeshConfiguration config, Environment environment) {
        if (config.getFlyway().isAutoMigrate()) {
            runFlyway(config);
        } else {
            log.info("Flyway autoMigrate is disabled — skipping migrations.");
        }

        Jdbi jdbi = new JdbiFactory().build(environment, config.getDatabase(), "rdmmesh");
        jdbi.installPlugin(new SqlObjectPlugin());
        // PostgresPlugin регистрирует argument factories / column mappers для
        // postgres-специфичных типов (UUID[], hstore, json). Workflow E5 использует
        // uuid[] (workflow.approval_task.candidate_users); другим модулям не мешает.
        jdbi.installPlugin(new PostgresPlugin());
        log.info("JDBI initialised against {} (SqlObjectPlugin + PostgresPlugin installed)",
                config.getDatabase().getUrl());

        // E14 round 8: глобальный лимит размера тела (@PreMatching, до auth и
        // чтения entity). Защищает неаутентифицированный OM-webhook от
        // memory-exhaustion (см. RequestSizeLimitFilter javadoc).
        environment.jersey().register(new bank.rdmmesh.app.security.RequestSizeLimitFilter());

        // E14.15 (F3, закрывает E14.8 §3 #3): servlet-уровневый hard-cap —
        // покрывает chunked-without-Content-Length, который JAX-RS-фильтр
        // выше пропускает. Регистрируется на /* раньше Jersey; overflow на
        // chunked-пути → RequestBodyTooLargeException → 413 через mapper.
        environment.servlets()
                .addFilter("requestBodyCap",
                        new bank.rdmmesh.app.security.RequestBodyCapFilter())
                .addMappingForUrlPatterns(
                        java.util.EnumSet.of(jakarta.servlet.DispatcherType.REQUEST),
                        /* isMatchAfter */ false,
                        "/*");
        environment.jersey().register(
                new bank.rdmmesh.app.security.RequestBodyTooLargeExceptionMapper());

        IdentityPort identityPort = buildIdentityPort(jdbi, config);
        registerJwtAuth(environment, identityPort);

        OwnershipPort ownershipPort = OwnershipModule.buildPort(jdbi);

        CatalogModule.Resources catalog = CatalogModule.build(jdbi, ownershipPort);
        environment.jersey().register(catalog.domains());
        environment.jersey().register(catalog.codeSets());
        environment.jersey().register(catalog.schemas());

        CatalogReadPort catalogReadPort = CatalogModule.buildReadPort(jdbi);
        // EventBus создаётся раньше: E14 round 11 — ClosureAdminResource
        // (authoring) эмитит ClosureRebuildDomainEvent, поэтому шина нужна
        // уже на этапе AuthoringModule.build.
        EventBus eventBus = new SyncEventBus();
        AuthoringModule.Resources authoring = AuthoringModule.build(
                jdbi, catalogReadPort, environment.getObjectMapper(), eventBus);
        environment.jersey().register(authoring.versions());
        environment.jersey().register(authoring.items());
        environment.jersey().register(authoring.diff());
        environment.jersey().register(authoring.closureAdmin());

        // E5 — Workflow. Lifecycle-write идёт через authoring (SPEC §3.3),
        // catalog read нужен workflow для resolve domainId по codesetId.
        VersionLifecyclePort lifecycle = AuthoringModule.buildLifecyclePort(jdbi);
        WorkflowModule.Resources workflow = WorkflowModule.build(
                jdbi, lifecycle, ownershipPort, catalogReadPort, eventBus);
        environment.jersey().register(workflow.transitions());
        environment.jersey().register(workflow.myTasks());

        // E6 — Publishing. Подписка на WorkflowTransitionDomainEvent с to=OWNER_APPROVED:
        // PublishingService автоматически создаёт snapshot, считает SHA-256 + HMAC и
        // переводит версию в PUBLISHED, попутно DEPRECATE'я предыдущую PUBLISHED.
        // E9 — Outbound webhooks: после publish'а PublishingService через
        // OutboundPort (OutboxOutboundAdapter) кладёт VersionPublishedEvent в
        // publishing.webhook_outbox для consumer-систем. WebhookDeliveryWorker —
        // Dropwizard Managed — дренирует outbox с retry/backoff.
        PublishedSnapshotPort snapshots = AuthoringModule.buildSnapshotPort(jdbi);
        WorkflowJournalPort workflowJournal = WorkflowModule.buildJournalPort(jdbi);
        SigningKeyPort signingKey = EnvSigningKeyAdapter.fromEnv(
                "RDM_HMAC_KEY",
                "rdmmesh-dev-hmac-key-change-me-in-prod-vault");
        WebhookKeyPort webhookKey = EnvWebhookKeyAdapter.withDevFallback(
                "rdmmesh-dev-webhook-key-change-me-in-prod-vault");
        PublishingModule.Resources publishing = PublishingModule.build(
                jdbi,
                lifecycle, snapshots, catalogReadPort, workflowJournal,
                signingKey, webhookKey,
                eventBus, environment.getObjectMapper());
        environment.jersey().register(publishing.verify());
        environment.jersey().register(publishing.subscriptions());
        environment.lifecycle().manage(publishing.deliveryWorker());

        // E7 — Ownership webhook receiver. POST /webhooks/om/ownership принимает
        // ChangeEvent'ы из OM Event Subscription. HMAC-ключ — отдельный от publishing'а
        // (тот для подписи snapshot'ов; этот — для inbound аутентификации webhook'а).
        // E14 round 6: fromEnv дополнительно читает RDM_OM_WEBHOOK_HMAC_KEY_PREVIOUS —
        // overlap-ключ на время zero-downtime ротации (docs/runbooks/hmac-key-rotation.md).
        CatalogMirrorPort catalogMirror = CatalogModule.buildMirrorPort(jdbi);
        SigningKeyPort omWebhookKey = EnvSigningKeyAdapter.fromEnv(
                "RDM_OM_WEBHOOK_HMAC_KEY",
                "rdmmesh-dev-om-webhook-key-change-me-in-prod-vault");
        environment.jersey().register(OwnershipModule.buildWebhookResource(
                jdbi, catalogMirror, ownershipPort, omWebhookKey, eventBus, environment.getObjectMapper()));

        // E8 — Distribution. Read-only consumer-API:
        //   GET /rdm/{domain}/{codeset}/{items|lookup|export}
        // ArchUnit-gates запрещают этому модулю любые DB writes.
        environment.jersey().register(
                DistributionModule.buildResource(jdbi, environment.getObjectMapper()));

        environment.jersey().register(new AuthResource());

        environment.healthChecks().register("info",
                new InfoHealthCheck(getName(), getClass().getPackage().getImplementationVersion()));

        // E10 — Audit. Глобальный subscriber на DomainEvent: WorkflowTransition,
        // VersionPublished, OwnershipChanged. INSERT в audit.audit_log идёт под
        // INSERT-only grants + триггерами против UPDATE/DELETE/TRUNCATE (V070);
        // UNIQUE (event_id, event_type) (V071) защищает от replay'ев.
        // E11.2d: AuditResource — paged GET /api/v1/audit под @RolesAllowed("RDM_ADMIN")
        // для UI-viewer'а (handoff E10 §3 #3).
        AuditModule.Resources audit = AuditModule.build(jdbi, eventBus, environment.getObjectMapper());
        environment.jersey().register(audit.resource());

        // E14 round 10 — immutable audit-archive (RustFS/S3, SPEC §3.8 V2).
        // ArchivePort disabled-by-default: пустой RDM_ARCHIVE_ENDPOINT →
        // no-op stub, сервис без RustFS не падает. POST /api/v1/audit/archive
        // (RDM_ADMIN) льёт месячный сегмент + пишет audit.archive_manifest —
        // источник истины для drop_audit_partition_if_archived(text) (V074).
        ArchivePort archivePort = ArchiveAdapters.fromEnv();
        environment.jersey().register(
                AuditModule.buildArchiveResource(jdbi, archivePort));
    }

    private static IdentityPort buildIdentityPort(Jdbi jdbi, RdmmeshConfiguration config) {
        var kc = config.getKeycloak();
        var om = config.getOpenmetadata();
        return IdentityModule.builder()
                .jdbi(jdbi)
                .keycloak(new IdentityModule.KeycloakSettings(
                        kc.getIssuerUri(),
                        kc.getJwksUri(),
                        kc.getAudience(),
                        kc.getUsernameClaim(),
                        kc.getGroupsClaim(),
                        kc.getRequiredClaims(),
                        kc.getJwksCacheTtl(),
                        kc.getClockSkew()))
                .openmetadata(new IdentityModule.OpenMetadataSettings(
                        om.getBaseUrl(),
                        om.getBotToken(),
                        om.getConnectTimeout(),
                        om.getRequestTimeout()))
                .build();
    }

    private static void registerJwtAuth(Environment environment, IdentityPort identityPort) {
        var authenticator = new JwtAuthenticator(identityPort);
        var filter = new OAuthCredentialAuthFilter.Builder<RdmmeshPrincipal>()
                .setAuthenticator(authenticator)
                .setAuthorizer(new RoleAuthorizer())
                .setPrefix("Bearer")
                .setRealm("rdmmesh")
                .buildAuthFilter();
        environment.jersey().register(new AuthDynamicFeature(filter));
        environment.jersey().register(new AuthValueFactoryProvider.Binder<>(RdmmeshPrincipal.class));
        environment.jersey().register(RolesAllowedDynamicFeature.class);
        log.info("JWT auth filter registered (Bearer + @RolesAllowed enabled)");
    }

    private static void runFlyway(RdmmeshConfiguration config) {
        var db = config.getDatabase();
        var fw = config.getFlyway();

        log.info("Running Flyway against {} (default schema {}, schemas {})",
                db.getUrl(), fw.getDefaultSchema(), fw.getSchemas());

        Flyway flyway = Flyway.configure()
                .dataSource(db.getUrl(), db.getUser(), db.getPassword())
                .locations(fw.getLocations().toArray(String[]::new))
                .schemas(fw.getSchemas().toArray(String[]::new))
                .defaultSchema(fw.getDefaultSchema())
                .createSchemas(true)
                .baselineOnMigrate(true)
                // Каждый bounded context добавляет миграции в свою директорию
                // (catalog/V0X0, authoring/V0X1, ...). Без out-of-order Flyway отказывается
                // применять V021 поверх уже-применённой V070, потому что 021 < 070.
                // SPEC §3.3: модули — независимые owner'ы своих schemas, поэтому это
                // ожидаемая, а не аномальная ситуация.
                .outOfOrder(true)
                .load();

        var result = flyway.migrate();
        log.info("Flyway: {} migrations applied (initialSchemaVersion={}, targetSchemaVersion={})",
                result.migrationsExecuted, result.initialSchemaVersion, result.targetSchemaVersion);
    }
}
