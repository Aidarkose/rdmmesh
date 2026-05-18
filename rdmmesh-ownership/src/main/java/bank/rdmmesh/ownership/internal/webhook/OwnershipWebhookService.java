package bank.rdmmesh.ownership.internal.webhook;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.eventbus.OwnershipChangedDomainEvent;
import bank.rdmmesh.api.port.CatalogMirrorPort;
import bank.rdmmesh.api.port.OwnershipPort;
import bank.rdmmesh.ownership.internal.PostgresOwnershipPort;
import bank.rdmmesh.ownership.internal.dao.ProcessedEventDao;
import bank.rdmmesh.spec.events.OwnershipChangedEvent;

/**
 * Принимает декодированные {@link OwnershipChangedEvent} из webhook-resource'а и применяет
 * их к catalog'у (для domain) или ownership'у (для table). Идемпотентность реализуется в
 * двух слоях:
 * <ol>
 *   <li>{@code ownership.processed_om_event} — журнал event_id, отсекает дубликаты на входе
 *       до изменения catalog/ownership;</li>
 *   <li>UNIQUE(asset_id, asset_type, om_user_id, role) на {@code rdm_asset_ownership} +
 *       UNIQUE(om_domain_id) на {@code catalog.domain} — фоновая защита, если события
 *       пришли одновременно.</li>
 * </ol>
 */
public final class OwnershipWebhookService {

    private static final Logger log = LoggerFactory.getLogger(OwnershipWebhookService.class);

    private final Jdbi jdbi;
    private final CatalogMirrorPort catalogMirror;
    private final OwnershipPort ownership;
    private final EventBus eventBus;

    public OwnershipWebhookService(
            Jdbi jdbi, CatalogMirrorPort catalogMirror, OwnershipPort ownership, EventBus eventBus) {
        this.jdbi = jdbi;
        this.catalogMirror = catalogMirror;
        this.ownership = ownership;
        this.eventBus = eventBus;
    }

    public Result handle(OwnershipChangedEvent event, byte[] rawBody) {
        String eventId = event.getEventId();
        String entityType = event.getEntityType() == null ? "" : event.getEntityType().value();
        String fqn = event.getFullyQualifiedName();
        String payloadSha = sha256Hex(rawBody);

        // E14 round 5.2: ОДНА tx на recordIfAbsent + mirror/ownership apply.
        // До round 5.2 split-tx между журналом и mirror'ом мог дать состояние
        // «event_id зафиксирован, mirror не обновлён» — следующий retry увидел бы
        // duplicate и данные навсегда потеряны.
        TxResult txResult = jdbi.inTransaction(handle -> {
            int rows = handle.attach(ProcessedEventDao.class)
                    .recordIfAbsent(eventId, entityType, fqn, event.getOccurredAt(), payloadSha);
            if (rows == 0) {
                log.info("ownership-webhook: duplicate event_id={} entity={} ignored",
                        eventId, entityType);
                return new TxResult(new Result(Outcome.DUPLICATE, eventId, null, null, null), null);
            }
            return switch (entityType) {
                case "domain" -> handleDomain(handle, event);
                case "table"  -> handleTable(handle, event);
                default       -> new TxResult(
                        new Result(Outcome.UNSUPPORTED, eventId, null, null,
                                "unsupported entity_type=" + entityType),
                        null);
            };
        });

        Result result = txResult.result();

        // EventBus.publish — ПОСЛЕ commit'а основной tx. Audit/listener'ы видят
        // зафиксированный mirror/ownership, не in-flight. Best-effort (SPEC §3.8).
        if (result.outcome() == Outcome.APPLIED && txResult.eventBusPayload() != null) {
            EventBusPayload p = txResult.eventBusPayload();
            publishDomainEvent(event, p.aggregateType(), p.aggregateId());
        }
        return result;
    }

    /**
     * Возврат из tx-lambda: основной Result (отдаём caller'у) + (опц.) payload
     * для EventBus.publish'а ПОСЛЕ commit'а. Раздельно, чтобы commit зафиксировался
     * сначала, а subscriber'ы (audit и др.) видели already-committed данные.
     */
    private record TxResult(Result result, EventBusPayload eventBusPayload) {}

    private record EventBusPayload(String aggregateType, UUID aggregateId) {}

    private TxResult handleDomain(Handle handle, OwnershipChangedEvent event) {
        UUID omDomainId = parseUuid(event.getEntityId(),
                "entity_id обязателен для entity_type=domain");
        String domainName = deriveDomainName(event);
        if (domainName == null) {
            return new TxResult(new Result(Outcome.BAD_REQUEST, event.getEventId(), null, null,
                    "не удалось определить name domain'а — fully_qualified_name пуст"), null);
        }
        var mirror = new CatalogMirrorPort.DomainMirror(
                omDomainId,
                domainName,
                /* displayName */ null,
                /* description */ null,
                /* labelRu */ null,
                /* labelEn */ null,
                /* tags */ null);
        var result = catalogMirror.upsertDomainFromOm(handle, mirror);
        log.info("ownership-webhook: domain op={} om_id={} name={}",
                result.op(), result.omDomainId(), domainName);
        return new TxResult(
                new Result(Outcome.APPLIED, event.getEventId(), entityRef("DOMAIN", result.id()),
                        null, "domain " + result.op().name().toLowerCase()),
                new EventBusPayload("DOMAIN", result.id()));
    }

    private TxResult handleTable(Handle handle, OwnershipChangedEvent event) {
        Optional<FqnParser.TableFqn> parsed = FqnParser.parseTable(event.getFullyQualifiedName());
        if (parsed.isEmpty()) {
            return new TxResult(new Result(Outcome.IGNORED, event.getEventId(), null, null,
                    "FQN не относится к rdmmesh: " + event.getFullyQualifiedName()), null);
        }
        FqnParser.TableFqn fqn = parsed.get();
        Optional<UUID> codesetId = catalogMirror.findCodeSetIdByFqn(fqn.domainName(), fqn.codesetName());
        if (codesetId.isEmpty()) {
            log.warn("ownership-webhook: CodeSet не найден для FQN {}.{} — игнорируем event_id={}",
                    fqn.domainName(), fqn.codesetName(), event.getEventId());
            return new TxResult(new Result(Outcome.UNKNOWN_ASSET, event.getEventId(), null, null,
                    "CodeSet rdmmesh." + fqn.domainName() + "." + fqn.codesetName() + " не найден"),
                    null);
        }

        Set<UUID> desiredOwners = parseUserIds(event.getOwners(), "owners");
        Set<UUID> desiredExperts = parseUserIds(event.getExperts(), "experts");
        Set<UUID> desiredApprovers = parseUserIds(event.getReviewers(), "reviewers");

        if (!(ownership instanceof PostgresOwnershipPort pg)) {
            throw new IllegalStateException(
                    "OwnershipWebhookService требует PostgresOwnershipPort,"
                            + " но получил " + ownership.getClass());
        }
        var delta = pg.computeDelta(
                handle, codesetId.get(), "CODESET", desiredOwners, desiredExperts, desiredApprovers);
        ownership.applyChangeEvent(handle, codesetId.get(), "CODESET", delta, event.getEventId());

        return new TxResult(
                new Result(Outcome.APPLIED, event.getEventId(),
                        entityRef("CODESET", codesetId.get()),
                        summary(delta), null),
                new EventBusPayload("CODESET", codesetId.get()));
    }

    private void publishDomainEvent(OwnershipChangedEvent event, String aggregateType, UUID aggregateId) {
        try {
            eventBus.publish(new OwnershipChangedDomainEvent(
                    UUID.randomUUID(),
                    OffsetDateTime.now(),
                    event,
                    aggregateId,
                    aggregateType));
        } catch (RuntimeException e) {
            log.warn("ownership-webhook: eventBus.publish failed for event_id={} ({}/{}): {}",
                    event.getEventId(), aggregateType, aggregateId, e.toString());
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static String deriveDomainName(OwnershipChangedEvent event) {
        if (event.getFullyQualifiedName() == null || event.getFullyQualifiedName().isBlank()) {
            return null;
        }
        return event.getFullyQualifiedName().trim();
    }

    private static UUID parseUuid(String s, String message) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(message + " (получено '" + s + "')", e);
        }
    }

    private static Set<UUID> parseUserIds(List<String> raw, String field) {
        if (raw == null) return Set.of();
        Set<UUID> out = new HashSet<>(raw.size());
        for (String s : raw) {
            try {
                out.add(UUID.fromString(s));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        field + "[" + s + "] — не UUID", e);
            }
        }
        return out;
    }

    private static String entityRef(String type, UUID id) {
        return type + ":" + id;
    }

    private static String summary(OwnershipPort.OwnershipDelta d) {
        return String.format(
                "owners(+%d/-%d) experts(+%d/-%d) approvers(+%d/-%d)",
                d.ownersAdded().size(), d.ownersRemoved().size(),
                d.expertsAdded().size(), d.expertsRemoved().size(),
                d.approversAdded().size(), d.approversRemoved().size());
    }

    private static String sha256Hex(byte[] body) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(body));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    public enum Outcome { APPLIED, DUPLICATE, IGNORED, UNKNOWN_ASSET, UNSUPPORTED, BAD_REQUEST }

    public record Result(Outcome outcome, String eventId, String entityRef, String summary, String note) {}
}
