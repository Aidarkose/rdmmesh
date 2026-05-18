package bank.rdmmesh.audit.resource;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;

import bank.rdmmesh.api.eventbus.AuditVerifyDomainEvent;
import bank.rdmmesh.api.eventbus.EventBus;
import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.audit.internal.AuditChainVerifier;
import bank.rdmmesh.audit.internal.AuditExportWriter;
import bank.rdmmesh.audit.internal.dao.AuditLogDao;
import io.dropwizard.auth.Auth;
import org.jdbi.v3.core.Jdbi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Эпик E11.2d (UI Audit viewer) — закрывает follow-up handoff E10 §3 #3.
 *
 * <pre>
 *   GET /api/v1/audit                                    @RolesAllowed("RDM_ADMIN")
 *     ?event_type=WORKFLOW_TRANSITION
 *     &amp;aggregate_type=VERSION
 *     &amp;aggregate_id=&lt;uuid&gt;
 *     &amp;actor=&lt;uuid&gt;
 *     &amp;from=&lt;ISO-8601 instant&gt;
 *     &amp;to=&lt;ISO-8601 instant&gt;
 *     &amp;q=&lt;substring of payload-&gt;&gt;'comment'&gt;
 *     &amp;page=1&amp;size=50          (max size 1000)
 * </pre>
 *
 * <p>Возвращает {@link Page} с {@code total} (точный count из БД), {@code page},
 * {@code size}, {@code items}. Каждый item — {@link AuditEntryDto} с полным
 * payload как {@code Map} (jsonb распарсен на сервере, чтобы клиент не парсил
 * вторично).
 *
 * <p>Frontend-доступ — {@code RDM_ADMIN} либо {@code RDM_AUDITOR} (E14 round 3,
 * закрывает open question E10 §6 #15). RDM_AUDITOR — read-only compliance-роль:
 * читает audit и запускает verify-chain, но не имеет прав admin'а
 * (subscriptions/closure-rebuild недоступны). SPEC §3.3 говорит «audit
 * подписан на event-bus, никого не читает» — это про write-side; read-side
 * через REST появляется здесь как minimal viable export.
 *
 * <p>Этот resource НЕ нарушает изоляцию audit-модуля: он использует только
 * {@link AuditLogDao} (свой же internal-пакет) и api-security
 * {@link RdmmeshPrincipal}. ArchUnit-rule {@code audit_only_depends_on_api_or_spec}
 * разрешает классы из {@code bank.rdmmesh.audit..} + api/spec/runtime.
 */
@Path("/audit")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"RDM_ADMIN", "RDM_AUDITOR"})
public final class AuditResource {

    private static final int DEFAULT_SIZE = 50;
    private static final int MAX_SIZE = 1000;

    /** Шаг pagination для StreamingOutput'а export'а. Выбран как баланс между
     *  числом round-trip'ов в БД и hold-в-памяти. На пилоте 1000 даёт ~100 KB
     *  JSON держится в JVM heap'е на каждой странице — приемлемо. */
    private static final int EXPORT_PAGE_SIZE = 1000;

    private static final DateTimeFormatter FILENAME_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private static final Logger log = LoggerFactory.getLogger(AuditResource.class);

    private final Jdbi jdbi;
    private final ObjectMapper json;
    private final AuditChainVerifier verifier;
    private final EventBus eventBus;

    public AuditResource(
            Jdbi jdbi, ObjectMapper json, AuditChainVerifier verifier, EventBus eventBus) {
        this.jdbi = jdbi;
        this.json = json;
        this.verifier = verifier;
        this.eventBus = eventBus;
    }

    @GET
    public Page list(
            @Auth RdmmeshPrincipal principal,
            @QueryParam("event_type") String eventType,
            @QueryParam("aggregate_type") String aggregateType,
            @QueryParam("aggregate_id") String aggregateIdRaw,
            @QueryParam("actor") String actorRaw,
            @QueryParam("from") String fromRaw,
            @QueryParam("to") String toRaw,
            @QueryParam("q") String freeText,
            @QueryParam("page") Integer page,
            @QueryParam("size") Integer size) {

        UUID aggregateId = parseUuid(aggregateIdRaw, "aggregate_id");
        UUID actor = parseUuid(actorRaw, "actor");
        OffsetDateTime fromTs = parseInstant(fromRaw, "from");
        OffsetDateTime toTs = parseInstant(toRaw, "to");

        int p = page == null || page < 0 ? 0 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (s < 1 || s > MAX_SIZE) {
            throw new WebApplicationException(
                    "size must be between 1 and " + MAX_SIZE,
                    Response.Status.BAD_REQUEST);
        }

        String freeTextPattern = (freeText == null || freeText.isBlank())
                ? null
                : "%" + freeText.trim().replace("%", "\\%").replace("_", "\\_") + "%";

        String etype = blankToNull(eventType);
        String atype = blankToNull(aggregateType);

        List<AuditLogDao.AuditEntry> rows = jdbi.withExtension(AuditLogDao.class, dao ->
                dao.findPaged(etype, atype, aggregateId, actor, fromTs, toTs, freeTextPattern,
                        s, p * s));

        long total = jdbi.withExtension(AuditLogDao.class, dao ->
                dao.countFiltered(etype, atype, aggregateId, actor, fromTs, toTs, freeTextPattern));

        List<AuditEntryDto> items = new ArrayList<>(rows.size());
        for (AuditLogDao.AuditEntry r : rows) {
            items.add(toDto(r));
        }
        return new Page(p, s, total, items);
    }

    private AuditEntryDto toDto(AuditLogDao.AuditEntry r) {
        Map<String, Object> payload = parsePayload(r.payload());
        return new AuditEntryDto(
                r.id(),
                r.eventId(),
                r.eventType(),
                r.aggregateType(),
                r.aggregateId(),
                r.actor(),
                r.occurredAt(),
                payload);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parsePayload(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            Object obj = json.readValue(raw, Object.class);
            if (obj instanceof Map) return (Map<String, Object>) obj;
            // Не-объектный payload (массив или скаляр) — заворачиваем в обёртку,
            // чтобы клиенту не пришлось различать два формата.
            return Map.of("value", obj);
        } catch (IOException e) {
            return Map.of("_raw", raw);
        }
    }

    private static UUID parseUuid(String s, String field) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw new WebApplicationException(
                    field + " must be a UUID", Response.Status.BAD_REQUEST);
        }
    }

    private static OffsetDateTime parseInstant(String s, String field) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s);
        } catch (DateTimeParseException e) {
            throw new WebApplicationException(
                    field + " must be ISO-8601 timestamp (e.g. 2026-05-11T00:00:00Z)",
                    Response.Status.BAD_REQUEST);
        }
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * E14 round 1 — verify hash-chain. Доступен {@code RDM_ADMIN} либо
     * {@code RDM_AUDITOR} (наследует class-level {@code @RolesAllowed}).
     *
     * <pre>
     *   GET /api/v1/audit/verify-chain
     *     [?from=&lt;id&gt;]  по умолчанию — min(id) (вся цепочка)
     *     [?to=&lt;id&gt;]    по умолчанию — max(id)
     * </pre>
     *
     * <p>Возвращает {@link VerifyChainResponse}: {@code verified=true} означает,
     * что вся выбранная подцепочка целостна — каждая запись имеет корректные
     * {@code prev_hash} (continuity) и {@code entry_hash} (integrity).
     *
     * <p>{@code firstBrokenAt} — id первой разрушенной записи; {@code reason} —
     * текстовое описание, {@code expectedHash}/{@code storedHash} — хеши на
     * месте разрыва. Если {@code verified=true}, эти поля {@code null}.
     *
     * <p>Защита от больших range'ей: размер range никак не лимитируется —
     * пользователь сам контролирует через {@code from}/{@code to}. На пилоте
     * (десятки/сотни записей) это не проблема. Для крупного prod'а будущий
     * round'ом V14 добавит batch-режим (verify по chunk'ам с anchor'ами).
     */
    @GET
    @Path("/verify-chain")
    public VerifyChainResponse verifyChain(
            @Auth RdmmeshPrincipal principal,
            @QueryParam("from") Long fromId,
            @QueryParam("to") Long toId) {

        Long minId = jdbi.withExtension(AuditLogDao.class, AuditLogDao::findMinId).orElse(null);
        Long maxId = jdbi.withExtension(AuditLogDao.class, AuditLogDao::findMaxId).orElse(null);

        if (minId == null || maxId == null) {
            return new VerifyChainResponse(0L, 0L, 0, true, null, null, null, null);
        }

        long from = fromId == null ? minId : fromId;
        long to = toId == null ? maxId : toId;
        if (from > to) {
            throw new WebApplicationException(
                    "from must be <= to", Response.Status.BAD_REQUEST);
        }

        // Anchor: hash записи перед range'ем. Если from = minId журнала, anchor null
        // (verify верит, что цепочка начинается чистой). Иначе — берём entry_hash
        // строки id=from-1, если она существует, либо null, если from опередил
        // существующий минимум (пользователь указал что-то меньше min'а).
        final long anchorBoundary = from - 1L;
        final String anchorPrev;
        if (from <= minId) {
            anchorPrev = null;
        } else {
            anchorPrev = jdbi.withExtension(AuditLogDao.class, dao ->
                    dao.findChainRange(anchorBoundary, anchorBoundary).stream()
                            .findFirst()
                            .map(AuditLogDao.ChainRow::entryHash)
                            .orElse(null));
        }

        List<AuditLogDao.ChainRow> rows = jdbi.withExtension(AuditLogDao.class,
                dao -> dao.findChainRange(from, to));

        AuditChainVerifier.Result r = verifier.verify(rows, anchorPrev);

        // E14 round 11 — event-coverage: фиксируем сам факт verify-chain
        // (кто/диапазон/исход) в журнале. Подписчик audit запишет это новой
        // append-row'й (id > to) — на уже-вычисленный r не влияет, цикла нет
        // (verify-chain — GET по запросу, не триггерится записью). Publish
        // best-effort: сбой не должен ломать ответ verify-chain (SPEC §3.8).
        try {
            eventBus.publish(new AuditVerifyDomainEvent(
                    UUID.randomUUID(),
                    OffsetDateTime.now(ZoneOffset.UTC),
                    principal.omUserId(),
                    from,
                    to,
                    r.verified(),
                    r.checked()));
        } catch (RuntimeException e) {
            log.warn("audit: AUDIT_VERIFY_CHAIN event publish failed: {}", e.toString());
        }

        return new VerifyChainResponse(
                from,
                to,
                r.checked(),
                r.verified(),
                r.firstBrokenAt(),
                r.reason(),
                r.expectedHash(),
                r.storedHash());
    }

    /**
     * E14 round 4 — audit-export endpoint. Закрывает follow-up E10 §3 #3 /
     * E11.2d §3 #3 для compliance-аудитора, которому нужен offline-snapshot
     * журнала.
     *
     * <pre>
     *   GET /api/v1/audit/export       @RolesAllowed("RDM_ADMIN","RDM_AUDITOR")
     *     ?format=csv|ndjson           (csv по умолчанию)
     *     + те же фильтры что и /audit (event_type, aggregate_type,
     *       aggregate_id, actor, from, to, q)
     * </pre>
     *
     * <p>Особенности:
     * <ul>
     *   <li><b>Streaming.</b> Ответ — {@code StreamingOutput} с pagination-loop'ом
     *       по {@code EXPORT_PAGE_SIZE=1000}. Heap'у нужно ~1 MB одновременно,
     *       не зависит от размера выгрузки.</li>
     *   <li><b>Snapshot consistency.</b> {@code snapshotMaxId = findMaxId()} в
     *       начале фиксирует «верх» журнала; concurrent INSERT'ы в audit_log
     *       поверх snapshot'а не попадают в выгрузку. ORDER BY {@code id ASC} даёт
     *       стабильный порядок.</li>
     *   <li><b>Content-Disposition.</b> Имя файла —
     *       {@code audit-YYYYMMDD-HHmmss.csv|ndjson} (UTC).</li>
     *   <li><b>CSV формат:</b> RFC 4180, header-row + N data-rows.</li>
     *   <li><b>NDJSON формат:</b> один JSON-object на строку, payload и
     *       payload_canonical inline (не escape-string).</li>
     * </ul>
     */
    @GET
    @Path("/export")
    public Response export(
            @Auth RdmmeshPrincipal principal,
            @QueryParam("event_type") String eventType,
            @QueryParam("aggregate_type") String aggregateType,
            @QueryParam("aggregate_id") String aggregateIdRaw,
            @QueryParam("actor") String actorRaw,
            @QueryParam("from") String fromRaw,
            @QueryParam("to") String toRaw,
            @QueryParam("q") String freeText,
            @QueryParam("format") String formatRaw) {

        UUID aggregateId = parseUuid(aggregateIdRaw, "aggregate_id");
        UUID actor = parseUuid(actorRaw, "actor");
        OffsetDateTime fromTs = parseInstant(fromRaw, "from");
        OffsetDateTime toTs = parseInstant(toRaw, "to");
        String etype = blankToNull(eventType);
        String atype = blankToNull(aggregateType);
        String freeTextPattern = (freeText == null || freeText.isBlank())
                ? null
                : "%" + freeText.trim().replace("%", "\\%").replace("_", "\\_") + "%";

        String format = (formatRaw == null || formatRaw.isBlank())
                ? "csv"
                : formatRaw.trim().toLowerCase(Locale.ROOT);
        if (!"csv".equals(format) && !"ndjson".equals(format)) {
            throw new WebApplicationException(
                    "format must be 'csv' or 'ndjson'", Response.Status.BAD_REQUEST);
        }

        long snapshotMaxId = jdbi.withExtension(AuditLogDao.class, AuditLogDao::findMaxId)
                .orElse(0L);

        StreamingOutput so = output -> writeExport(
                output, format, snapshotMaxId,
                etype, atype, aggregateId, actor, fromTs, toTs, freeTextPattern);

        String filename = "audit-"
                + OffsetDateTime.now(ZoneOffset.UTC).format(FILENAME_TIMESTAMP)
                + "." + format;
        String mediaType = "csv".equals(format) ? "text/csv" : "application/x-ndjson";

        return Response.ok(so)
                .type(mediaType + "; charset=UTF-8")
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .header("X-RDM-Snapshot-Max-Id", Long.toString(snapshotMaxId))
                .build();
    }

    private void writeExport(
            java.io.OutputStream output,
            String format,
            long snapshotMaxId,
            String etype,
            String atype,
            UUID aggregateId,
            UUID actor,
            OffsetDateTime fromTs,
            OffsetDateTime toTs,
            String freeTextPattern) throws IOException {

        // BufferedWriter снижает количество syscall'ов; flush в конце через
        // try-with-resources (закрытие BufferedWriter → flush + закрытие OSW).
        try (Writer w = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8))) {
            if ("csv".equals(format)) {
                AuditExportWriter.writeCsvHeader(w);
            }
            if (snapshotMaxId == 0) {
                // Пустой журнал. Header (если CSV) уже выписан, body нет.
                return;
            }
            long offset = 0;
            while (true) {
                final long offsetCopy = offset;
                List<AuditLogDao.ExportRow> page = jdbi.withExtension(AuditLogDao.class, dao ->
                        dao.findExportPage(snapshotMaxId,
                                etype, atype, aggregateId, actor, fromTs, toTs, freeTextPattern,
                                EXPORT_PAGE_SIZE, offsetCopy));
                if (page.isEmpty()) {
                    return;
                }
                for (AuditLogDao.ExportRow r : page) {
                    if ("csv".equals(format)) {
                        AuditExportWriter.writeCsvRow(w, r);
                    } else {
                        AuditExportWriter.writeNdjsonRow(w, r);
                    }
                }
                if (page.size() < EXPORT_PAGE_SIZE) {
                    return;
                }
                offset += EXPORT_PAGE_SIZE;
            }
        }
    }

    public record Page(
            @JsonProperty("page") int page,
            @JsonProperty("size") int size,
            @JsonProperty("total") long total,
            @JsonProperty("items") List<AuditEntryDto> items) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VerifyChainResponse(
            @JsonProperty("from") long from,
            @JsonProperty("to") long to,
            @JsonProperty("checked") int checked,
            @JsonProperty("verified") boolean verified,
            @JsonProperty("first_broken_at") Long firstBrokenAt,
            @JsonProperty("reason") String reason,
            @JsonProperty("expected_hash") String expectedHash,
            @JsonProperty("stored_hash") String storedHash) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AuditEntryDto(
            @JsonProperty("id") long id,
            @JsonProperty("event_id") UUID eventId,
            @JsonProperty("event_type") String eventType,
            @JsonProperty("aggregate_type") String aggregateType,
            @JsonProperty("aggregate_id") UUID aggregateId,
            @JsonProperty("actor") UUID actor,
            @JsonProperty("occurred_at") OffsetDateTime occurredAt,
            @JsonProperty("payload") Map<String, Object> payload) {}
}
