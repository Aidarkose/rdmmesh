package bank.rdmmesh.publishing.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.OffsetDateTime;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.eventbus.VersionPublishedDomainEvent;
import bank.rdmmesh.api.eventbus.WorkflowTransitionDomainEvent;
import bank.rdmmesh.api.port.CatalogReadPort;
import bank.rdmmesh.api.port.CatalogReadPort.CodeSetSnapshot;
import bank.rdmmesh.api.port.CatalogReadPort.DomainSnapshot;
import bank.rdmmesh.api.port.OutboundPort;
import bank.rdmmesh.api.port.PublishedSnapshotPort;
import bank.rdmmesh.api.port.VersionLifecyclePort;
import bank.rdmmesh.api.port.VersionLifecyclePort.PublishedVersionDetails;
import bank.rdmmesh.api.port.VersionLifecyclePort.VersionSnapshot;
import bank.rdmmesh.api.port.WorkflowJournalPort;
import bank.rdmmesh.spec.events.VersionPublishedEvent;

/**
 * Эпик E6 (Publishing) — авто-publish после OWNER_APPROVED.
 *
 * <p>Подписывается на {@link WorkflowTransitionDomainEvent} с фильтром
 * {@code payload.to == OWNER_APPROVED} и:
 * <ol>
 *   <li>Читает canonical bytes снапшота через {@link PublishedSnapshotPort};</li>
 *   <li>Считает {@code content_hash = sha256_hex(bytes)};</li>
 *   <li>Считает {@code approval_signature = hmac_sha256_hex(content_hash + approver + ts, key)};</li>
 *   <li>Атомарно публикует версию (CAS OWNER_APPROVED → PUBLISHED + content_hash + signature + published_by/at);</li>
 *   <li>Если у CodeSet'а была предыдущая PUBLISHED — переводит её в DEPRECATED;</li>
 *   <li>Логирует оба system-перехода в {@code workflow.workflow_transition}.</li>
 * </ol>
 *
 * <p><b>Атомарность (E14 round 5.1).</b> Все 5 mutation'ов идут в одной
 * Postgres tx через {@link Jdbi#inTransaction}:
 * <ul>
 *   <li>{@code lifecycle.publish(handle, ...)} — CAS OWNER_APPROVED → PUBLISHED
 *       + content_hash + signature (authoring schema);</li>
 *   <li>{@code journal.recordSystemTransition(handle, ...)} — publish-system-action
 *       INSERT (workflow schema);</li>
 *   <li>{@code lifecycle.deprecate(handle, ...)} — auto-deprecate предыдущей PUBLISHED
 *       (если есть);</li>
 *   <li>{@code journal.recordSystemTransition(handle, ...)} — deprecate-system-action
 *       INSERT;</li>
 *   <li>{@code outbound.enqueueVersionPublished(handle, ...)} — outbox INSERT
 *       (publishing schema).</li>
 * </ul>
 * Если любая из 5 операций упадёт — все 5 rollback'аются, ничего не остаётся
 * inconsistent (status, журнал, outbox).
 *
 * <p><b>EventBus.publish — после commit'а.</b> Подписчики (audit-service)
 * пишут в свои схемы в собственных tx. Этот шаг best-effort (SPEC §3.8):
 * append-failure audit'а не должен блокировать business-операцию. Сценарий
 * «publish прошёл, audit-row пропущен» компенсируется реконструкцией из
 * workflow.workflow_transition.
 *
 * <p><b>Идемпотентность.</b> Если событие пришло повторно (audit replay, ручной
 * trigger), {@link VersionLifecyclePort#publish} вернёт false (статус уже не
 * OWNER_APPROVED) — обработка корректно отвалится без побочных эффектов.
 */
public final class PublishingService {

    private static final Logger log = LoggerFactory.getLogger(PublishingService.class);

    /** SYSTEM-актор для лога system-переходов. */
    public static final UUID SYSTEM_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000000");

    /** Версия event-схемы {@code VersionPublishedEvent} (см. spec/events/...). */
    private static final int EVENT_SCHEMA_VERSION = 1;

    private final Jdbi jdbi;
    private final VersionLifecyclePort lifecycle;
    private final PublishedSnapshotPort snapshots;
    private final CatalogReadPort catalog;
    private final WorkflowJournalPort journal;
    private final HmacSigner signer;
    private final OutboundPort outbound;
    private final EventBus eventBus;

    public PublishingService(
            Jdbi jdbi,
            VersionLifecyclePort lifecycle,
            PublishedSnapshotPort snapshots,
            CatalogReadPort catalog,
            WorkflowJournalPort journal,
            HmacSigner signer,
            OutboundPort outbound,
            EventBus eventBus) {
        this.jdbi = jdbi;
        this.lifecycle = lifecycle;
        this.snapshots = snapshots;
        this.catalog = catalog;
        this.journal = journal;
        this.signer = signer;
        this.outbound = outbound;
        this.eventBus = eventBus;
    }

    /** Подписать сервис на события о приходе OWNER_APPROVED. */
    public void registerOn(EventBus bus) {
        bus.subscribe(WorkflowTransitionDomainEvent.class, this::onTransition);
    }

    void onTransition(WorkflowTransitionDomainEvent event) {
        if (event.payload() == null || event.payload().getTo() == null) return;
        String to = event.payload().getTo().value();
        if (!"OWNER_APPROVED".equals(to)) return;

        UUID versionId = UUID.fromString(event.payload().getVersionId());
        UUID approver  = UUID.fromString(event.payload().getActor());
        autoPublish(versionId, approver);
    }

    /** Доступно для unit/IT-тестов и для возможного manual re-trigger в админ-консоли (V1+). */
    public PublishOutcome autoPublish(UUID versionId, UUID approverOmUserId) {
        VersionSnapshot version = lifecycle.findVersion(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown version: " + versionId));
        if (!"OWNER_APPROVED".equals(version.status())) {
            log.warn("publishing: version {} в статусе {} — пропускаю auto-publish", versionId, version.status());
            return PublishOutcome.SKIPPED;
        }

        CodeSetSnapshot cs = catalog.findCodeSet(version.codesetId())
                .orElseThrow(() -> new IllegalStateException(
                        "CodeSet missing for version " + versionId));

        // Pre-tx прочитанные read'ы: hash, signature, prev PUBLISHED. canonical
        // bytes — derived из items'ов (read-only); подпись — pure; prev — read до
        // CAS, иначе после publish findLatestPublished вернёт уже эту versionId.
        byte[] canonical = snapshots.canonicalSnapshotBytes(versionId);
        String contentHash = HmacSigner.sha256Hex(canonical);
        String iso = Instant.now().toString();
        String signature = signer.signApproval(contentHash, approverOmUserId.toString(), iso);
        Optional<VersionSnapshot> prev = lifecycle.findLatestPublished(version.codesetId());

        // E14 round 5.1: ОДНА tx на publish + journal + deprecate + journal2 + outbox.
        // Сборка VersionPublishedEvent — тоже внутри tx, потому что читает
        // только что записанные publish-поля через shared handle.
        PublishTxResult txResult = jdbi.inTransaction(handle -> {
            boolean ok = lifecycle.publish(
                    handle, versionId, contentHash, signature, approverOmUserId);
            if (!ok) {
                return new PublishTxResult(true, null);
            }

            // Журнал publish'а.
            journal.recordSystemTransition(
                    handle,
                    versionId, version.codesetId(), cs.domainId(),
                    "OWNER_APPROVED", "PUBLISHED", "publish",
                    SYSTEM_ACTOR, "auto-publish after owner_approve");

            // Auto-DEPRECATE предыдущей PUBLISHED (если есть и не та же самая).
            prev.ifPresent(p -> {
                if (!p.id().equals(versionId)) {
                    boolean dep = lifecycle.deprecate(handle, p.id());
                    if (dep) {
                        journal.recordSystemTransition(
                                handle,
                                p.id(), version.codesetId(), cs.domainId(),
                                "PUBLISHED", "DEPRECATED", "deprecate",
                                SYSTEM_ACTOR, "auto-deprecate, superseded by " + versionId);
                    }
                }
            });

            // Outbound enqueue. buildPublishedEvent читает только что записанные
            // publish-поля (content_hash, published_at, published_by) через
            // shared handle — uncommitted writes в той же tx видны.
            VersionPublishedEvent event;
            try {
                event = buildPublishedEvent(handle, versionId, version.codesetId(), cs, prev.orElse(null));
                outbound.enqueueVersionPublished(handle, event);
            } catch (RuntimeException e) {
                // Outbound сбой → rollback всей tx. Это compliance-trade-off:
                // лучше не публиковать вовсе, чем оставить status=PUBLISHED без
                // outbox-записи. Минимальный event для audit построим уже после
                // rollback'а из ничего — будет SKIPPED.
                throw e;
            }
            return new PublishTxResult(false, event);
        });

        if (txResult.skipped()) {
            log.warn("publishing: CAS publish для {} вернул false (concurrent transition?)", versionId);
            return PublishOutcome.SKIPPED;
        }

        log.info("publishing: PUBLISHED version_id={} codeset_id={} content_hash={} approver={}",
                versionId, version.codesetId(), contentHash, approverOmUserId);

        // Audit (E10): публикуем VersionPublishedDomainEvent в in-process bus
        // ПОСЛЕ commit'а основной tx. Audit-subscriber пишет в свою схему в своей
        // tx — best-effort (SPEC §3.8).
        try {
            VersionPublishedEvent forBus = txResult.event() != null
                    ? txResult.event()
                    : minimalPublishedEvent(versionId, version.codesetId(), cs, approverOmUserId, contentHash, signature);
            eventBus.publish(new VersionPublishedDomainEvent(
                    UUID.fromString(forBus.getEventId()),
                    OffsetDateTime.now(),
                    forBus));
        } catch (RuntimeException e) {
            log.warn("publishing: eventBus.publish(VersionPublishedDomainEvent) failed for {}: {}",
                    versionId, e.toString());
        }

        return PublishOutcome.PUBLISHED;
    }

    /**
     * Результат write-tx autoPublish. skipped=true → CAS вернул false
     * (concurrent transition); skipped=false + event → все 5 операций commit'нуты.
     */
    private record PublishTxResult(boolean skipped, VersionPublishedEvent event) {}

    /**
     * Минимальный payload, если основной {@link #buildPublishedEvent} не собрался
     * (например, упало чтение PublishedDetails). Содержит только обязательные поля
     * spec-схемы (event_id, schema_version, *_id, version, hash, signature, published_at)
     * — этого достаточно для audit-записи, но недостаточно для outbound payload'а.
     */
    private VersionPublishedEvent minimalPublishedEvent(
            UUID versionId, UUID codesetId, CodeSetSnapshot cs,
            UUID approver, String contentHash, String signature) {
        return new VersionPublishedEvent()
                .withEventId(UUID.randomUUID().toString())
                .withSchemaVersion(EVENT_SCHEMA_VERSION)
                .withDomainId(cs.domainId().toString())
                .withCodesetId(codesetId.toString())
                .withVersionId(versionId.toString())
                .withVersion("?")
                .withContentHash(contentHash)
                .withApprovalSignature(signature)
                .withPublishedAt(Instant.now().toString())
                .withPublishedBy(approver.toString());
    }

    private VersionPublishedEvent buildPublishedEvent(
            org.jdbi.v3.core.Handle handle,
            UUID versionId, UUID codesetId, CodeSetSnapshot cs, VersionSnapshot prev) {
        // E14 round 5.1: shared handle — read только что записанных publish-полей
        // в той же tx (separate jdbi-call увидел бы pre-publish state).
        PublishedVersionDetails details = lifecycle.findPublishedDetails(handle, versionId)
                .orElseThrow(() -> new IllegalStateException(
                        "версия " + versionId + " не в PUBLISHED после publish CAS"));
        DomainSnapshot domain = catalog.findDomain(cs.domainId()).orElse(null);
        VersionPublishedEvent ev = new VersionPublishedEvent()
                .withEventId(UUID.randomUUID().toString())
                .withSchemaVersion(EVENT_SCHEMA_VERSION)
                .withDomainId(cs.domainId().toString())
                .withCodesetId(codesetId.toString())
                .withVersionId(versionId.toString())
                .withVersion(details.version())
                .withContentHash(details.contentHash())
                .withApprovalSignature(details.approvalSignature())
                .withPublishedAt(details.publishedAt() == null
                        ? Instant.now().toString()
                        : details.publishedAt().toString());
        if (domain != null) ev.setDomainName(domain.name());
        ev.setCodesetName(cs.name());
        if (details.publishedBy() != null) ev.setPublishedBy(details.publishedBy().toString());
        if (details.itemCount() != null) ev.setItemCount(details.itemCount());
        if (prev != null && !prev.id().equals(versionId)) {
            ev.setPreviousVersion(prev.version());
        }
        return ev;
    }

    /**
     * Проверка целостности published-версии — для GET /versions/&#123;id&#125;/verify.
     * Пересчитывает content_hash из текущих items и сверяет с хранимым.
     */
    public VerifyResult verify(UUID versionId) {
        VersionSnapshot version = lifecycle.findVersion(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown version: " + versionId));
        if (!"PUBLISHED".equals(version.status()) && !"DEPRECATED".equals(version.status())) {
            return new VerifyResult(false, false, null, null,
                    "Версия в статусе " + version.status() + " — verify применим только к PUBLISHED/DEPRECATED");
        }
        byte[] canonical = snapshots.canonicalSnapshotBytes(versionId);
        String computed = HmacSigner.sha256Hex(canonical);
        String stored = lifecycle.findStoredContentHash(versionId).orElse(null);
        boolean verified = stored != null && stored.equalsIgnoreCase(computed);
        return new VerifyResult(true, verified, computed, stored, null);
    }

    public enum PublishOutcome { PUBLISHED, SKIPPED }

    public record VerifyResult(
            boolean applicable,
            boolean verified,
            String computedHash,
            String storedHash,
            String note) {}
}
