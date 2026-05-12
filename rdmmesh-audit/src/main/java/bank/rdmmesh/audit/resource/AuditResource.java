package bank.rdmmesh.audit.resource;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
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

import bank.rdmmesh.api.security.RdmmeshPrincipal;
import bank.rdmmesh.audit.internal.AuditChainVerifier;
import bank.rdmmesh.audit.internal.dao.AuditLogDao;
import io.dropwizard.auth.Auth;
import org.jdbi.v3.core.Jdbi;

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
 * <p>Frontend-доступ — только {@code RDM_ADMIN}. SPEC §3.3 говорит «audit
 * подписан на event-bus, никого не читает» — это про write-side; read-side
 * через REST появляется здесь как minimal viable export. {@code RDM_AUDITOR}
 * как отдельная роль ещё не введена в Keycloak realm (open question E10 §6 #15);
 * до того момента admin'ы — единственные потребители.
 *
 * <p>Этот resource НЕ нарушает изоляцию audit-модуля: он использует только
 * {@link AuditLogDao} (свой же internal-пакет) и api-security
 * {@link RdmmeshPrincipal}. ArchUnit-rule {@code audit_only_depends_on_api_or_spec}
 * разрешает классы из {@code bank.rdmmesh.audit..} + api/spec/runtime.
 */
@Path("/audit")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed("RDM_ADMIN")
public final class AuditResource {

    private static final int DEFAULT_SIZE = 50;
    private static final int MAX_SIZE = 1000;

    private final Jdbi jdbi;
    private final ObjectMapper json;
    private final AuditChainVerifier verifier;

    public AuditResource(Jdbi jdbi, ObjectMapper json, AuditChainVerifier verifier) {
        this.jdbi = jdbi;
        this.json = json;
        this.verifier = verifier;
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
     * E14 round 1 — verify hash-chain. Открыт только {@code RDM_ADMIN}.
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
