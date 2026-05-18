package bank.rdmmesh.audit.internal.dao;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.jdbi.v3.core.mapper.reflect.ColumnName;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO для {@code audit.audit_log}. Только INSERT и SELECT — UPDATE/DELETE запрещены
 * на уровне Postgres (триггеры BEFORE UPDATE/DELETE/TRUNCATE + REVOKE на роль
 * rdmmesh_app, миграция V070).
 *
 * <p>{@code ON CONFLICT DO NOTHING} использует UNIQUE-констрейнт
 * {@code (event_id, event_type, occurred_at)}. До V073 это был отдельный индекс
 * {@code audit_log_event_id_uq (event_id, event_type)} (V071); V073 RANGE-партиционировал
 * таблицу по {@code occurred_at}, а Postgres требует включать ключ партиции в каждый
 * UNIQUE — поэтому {@code occurred_at} добавлен в конфликт-таргет. Идемпотентность
 * сохранена: для replay'я одного логического события {@code occurred_at} стабилен
 * (это {@code event.occurredAt()}, не время вставки), как и event_id+event_type.
 */
public interface AuditLogDao {

    @SqlUpdate("""
            INSERT INTO audit.audit_log
                (event_id, event_type, aggregate_type, aggregate_id, actor, occurred_at,
                 payload, payload_canonical, prev_hash, entry_hash)
            VALUES
                (:eventId, :eventType, :aggregateType, :aggregateId, :actor, :occurredAt,
                 CAST(:payload AS jsonb), :payloadCanonical, :prevHash, :entryHash)
            ON CONFLICT (event_id, event_type, occurred_at) DO NOTHING
            """)
    int insert(
            @Bind("eventId") UUID eventId,
            @Bind("eventType") String eventType,
            @Bind("aggregateType") String aggregateType,
            @Bind("aggregateId") UUID aggregateId,
            @Bind("actor") UUID actor,
            @Bind("occurredAt") OffsetDateTime occurredAt,
            @Bind("payload") String payloadJson,
            @Bind("payloadCanonical") String payloadCanonical,
            @Bind("prevHash") String prevHash,
            @Bind("entryHash") String entryHash);

    /** Tail предыдущей записи цепочки — используется AuditService при write'е новой row. */
    @SqlQuery("""
            SELECT entry_hash
              FROM audit.audit_log
             ORDER BY id DESC
             LIMIT 1
            """)
    java.util.Optional<String> findLastEntryHash();

    /**
     * Range для verify-chain endpoint'а. Возвращает rows в id-ASC порядке —
     * это естественный порядок цепочки, по которому AuditService её и писал.
     * Берём только поля, нужные для пересчёта hash'а (event_id, event_type,
     * payload_canonical, occurred_at) + сами hash'ы для сравнения.
     */
    @SqlQuery("""
            SELECT id, event_id, event_type, occurred_at,
                   payload_canonical, prev_hash, entry_hash
              FROM audit.audit_log
             WHERE id >= :fromId
               AND id <= :toId
             ORDER BY id ASC
            """)
    @RegisterConstructorMapper(ChainRow.class)
    List<ChainRow> findChainRange(@Bind("fromId") long fromId, @Bind("toId") long toId);

    /** Самый ранний id в журнале — используется верификатором как default `from`. */
    @SqlQuery("SELECT min(id) FROM audit.audit_log")
    java.util.Optional<Long> findMinId();

    /** Самый поздний id — используется верификатором как default `to`. */
    @SqlQuery("SELECT max(id) FROM audit.audit_log")
    java.util.Optional<Long> findMaxId();

    /**
     * Hash-chain row — минимальный slice {@code audit_log} для пересчёта SHA-256.
     * Поля совпадают с теми, что участвуют в canonical_input
     * (см. {@code bank.rdmmesh.audit.internal.AuditChainHasher#computeEntryHash}).
     */
    record ChainRow(
            long id,
            @ColumnName("event_id") UUID eventId,
            @ColumnName("event_type") String eventType,
            @ColumnName("occurred_at") OffsetDateTime occurredAt,
            @ColumnName("payload_canonical") String payloadCanonical,
            @ColumnName("prev_hash") String prevHash,
            @ColumnName("entry_hash") String entryHash) {}

    @SqlQuery("""
            SELECT id, event_id, event_type, aggregate_type, aggregate_id, actor,
                   occurred_at, payload::text AS payload
              FROM audit.audit_log
             WHERE event_id = :eventId AND event_type = :eventType
            """)
    @RegisterConstructorMapper(AuditEntry.class)
    List<AuditEntry> findByEvent(@Bind("eventId") UUID eventId, @Bind("eventType") String eventType);

    /**
     * Постраничный listing с опциональными фильтрами (любой параметр может быть
     * null — тогда фильтр не накладывается). Используется V14-ish audit-export
     * REST endpoint'ом (handoff E10 §3 #3): admin/auditor может смотреть журнал
     * через UI без psql-доступа.
     *
     * <p>Сортировка — {@code occurred_at DESC, id DESC} (стабильный tie-breaker
     * через PK BIGSERIAL). Существующие индексы (event_type/aggregate/actor) с
     * {@code occurred_at DESC} покрывают типовые фильтры.
     *
     * <p>{@code freeText} (если непустой) ищет по полю {@code payload->>'comment'}
     * через GIN-индекс {@code audit_log_payload_gin_ix} (V070): мы делаем
     * {@code payload @> jsonb_build_object('comment', :freeText)}-сравнение,
     * только это даёт **точное** совпадение, не substring. Для «начинается с»
     * пользователь дописывает {@code %} вручную через отдельный LIKE — но в
     * MVP-версии достаточно contains-by-key через JSONB containment.
     */
    @SqlQuery("""
            SELECT id, event_id, event_type, aggregate_type, aggregate_id, actor,
                   occurred_at, payload::text AS payload
              FROM audit.audit_log
             WHERE (:eventType     IS NULL OR event_type     = :eventType)
               AND (:aggregateType IS NULL OR aggregate_type = :aggregateType)
               AND (:aggregateId   IS NULL OR aggregate_id   = :aggregateId)
               AND (:actor         IS NULL OR actor          = :actor)
               AND (:fromTs        IS NULL OR occurred_at   >= :fromTs)
               AND (:toTs          IS NULL OR occurred_at   <  :toTs)
               AND (:freeText      IS NULL OR payload->>'comment' ILIKE :freeText)
             ORDER BY occurred_at DESC, id DESC
             LIMIT :limit OFFSET :offset
            """)
    @RegisterConstructorMapper(AuditEntry.class)
    List<AuditEntry> findPaged(
            @Bind("eventType") String eventType,
            @Bind("aggregateType") String aggregateType,
            @Bind("aggregateId") UUID aggregateId,
            @Bind("actor") UUID actor,
            @Bind("fromTs") OffsetDateTime fromTs,
            @Bind("toTs") OffsetDateTime toTs,
            @Bind("freeText") String freeTextLikePattern,
            @Bind("limit") int limit,
            @Bind("offset") int offset);

    @SqlQuery("""
            SELECT count(*)
              FROM audit.audit_log
             WHERE (:eventType     IS NULL OR event_type     = :eventType)
               AND (:aggregateType IS NULL OR aggregate_type = :aggregateType)
               AND (:aggregateId   IS NULL OR aggregate_id   = :aggregateId)
               AND (:actor         IS NULL OR actor          = :actor)
               AND (:fromTs        IS NULL OR occurred_at   >= :fromTs)
               AND (:toTs          IS NULL OR occurred_at   <  :toTs)
               AND (:freeText      IS NULL OR payload->>'comment' ILIKE :freeText)
            """)
    long countFiltered(
            @Bind("eventType") String eventType,
            @Bind("aggregateType") String aggregateType,
            @Bind("aggregateId") UUID aggregateId,
            @Bind("actor") UUID actor,
            @Bind("fromTs") OffsetDateTime fromTs,
            @Bind("toTs") OffsetDateTime toTs,
            @Bind("freeText") String freeTextLikePattern);

    /** Read-row для интеграционных тестов / future audit-export endpoint'а. */
    record AuditEntry(
            long id,
            @ColumnName("event_id") UUID eventId,
            @ColumnName("event_type") String eventType,
            @ColumnName("aggregate_type") String aggregateType,
            @ColumnName("aggregate_id") UUID aggregateId,
            UUID actor,
            @ColumnName("occurred_at") OffsetDateTime occurredAt,
            String payload) {}

    /**
     * E14 round 4 — стриминговый export. Те же фильтры что и {@link #findPaged},
     * но с двумя дополнениями:
     * <ul>
     *   <li>{@code id <= :snapshotMaxId} — снимок журнала на момент старта
     *       export'а; concurrent INSERT'ы в audit_log поверх snapshot'а не
     *       попадают в выгрузку, что устраняет дубликаты на границах страниц.</li>
     *   <li>ORDER BY {@code id ASC} — стабильный порядок по PK; index scan по
     *       BIGSERIAL даёт O(1) seek для каждой следующей страницы.</li>
     * </ul>
     * <p>Возвращает {@link ExportRow} — расширенный AuditEntry с
     * {@code payload_canonical} (нужен compliance-аудитору для пересчёта
     * hash-chain независимо от backend'а).
     */
    @SqlQuery("""
            SELECT id, event_id, event_type, aggregate_type, aggregate_id, actor,
                   occurred_at, payload::text AS payload, payload_canonical,
                   prev_hash, entry_hash
              FROM audit.audit_log
             WHERE id <= :snapshotMaxId
               AND (:eventType     IS NULL OR event_type     = :eventType)
               AND (:aggregateType IS NULL OR aggregate_type = :aggregateType)
               -- CAST'ы: типизированный nullable-параметр, чей ПЕРВЫЙ
               -- синтаксический инстанс — `:p IS NULL`, оставляет Postgres
               -- без типа на parse → "could not determine data type"
               -- (E14 round 10: findExportPage с непустыми fromTs/toTs;
               -- E14.4-export не ловил — даты не передавались).
               AND (CAST(:aggregateId AS uuid)        IS NULL OR aggregate_id = CAST(:aggregateId AS uuid))
               AND (CAST(:actor       AS uuid)        IS NULL OR actor        = CAST(:actor AS uuid))
               AND (CAST(:fromTs      AS timestamptz) IS NULL OR occurred_at >= CAST(:fromTs AS timestamptz))
               AND (CAST(:toTs        AS timestamptz) IS NULL OR occurred_at <  CAST(:toTs AS timestamptz))
               AND (:freeText      IS NULL OR payload->>'comment' ILIKE :freeText)
             ORDER BY id ASC
             LIMIT :limit OFFSET :offset
            """)
    @RegisterConstructorMapper(ExportRow.class)
    List<ExportRow> findExportPage(
            @Bind("snapshotMaxId") long snapshotMaxId,
            @Bind("eventType") String eventType,
            @Bind("aggregateType") String aggregateType,
            @Bind("aggregateId") UUID aggregateId,
            @Bind("actor") UUID actor,
            @Bind("fromTs") OffsetDateTime fromTs,
            @Bind("toTs") OffsetDateTime toTs,
            @Bind("freeText") String freeTextLikePattern,
            @Bind("limit") int limit,
            @Bind("offset") long offset);

    /**
     * Read-row для audit-export endpoint'а. Включает hash-chain поля и
     * canonical payload — compliance-аудитор должен иметь полный набор
     * для независимого verify-цепочки оффлайн.
     */
    record ExportRow(
            long id,
            @ColumnName("event_id") UUID eventId,
            @ColumnName("event_type") String eventType,
            @ColumnName("aggregate_type") String aggregateType,
            @ColumnName("aggregate_id") UUID aggregateId,
            UUID actor,
            @ColumnName("occurred_at") OffsetDateTime occurredAt,
            String payload,
            @ColumnName("payload_canonical") String payloadCanonical,
            @ColumnName("prev_hash") String prevHash,
            @ColumnName("entry_hash") String entryHash) {}
}
