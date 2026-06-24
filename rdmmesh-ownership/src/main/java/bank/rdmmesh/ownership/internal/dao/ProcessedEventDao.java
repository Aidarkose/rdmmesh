package bank.rdmmesh.ownership.internal.dao;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

/**
 * DAO для {@code ownership.processed_om_event} (V061). Нужен для идемпотентности webhook'а
 * на уровне HTTP-приёма: по {@code event_id} проверяем, не приходил ли он раньше, и только
 * после успешной обработки фиксируем INSERT'ом. UNIQUE(event_id) на PRIMARY KEY гарантирует,
 * что параллельные дубликаты не оба «обработают» событие.
 */
public interface ProcessedEventDao {

    @SqlQuery(
            "SELECT EXISTS (SELECT 1 FROM ownership.processed_om_event"
                    + " WHERE event_id = :eventId)")
    boolean exists(@Bind("eventId") String eventId);

    /**
     * Идемпотентный INSERT — если строка с тем же event_id уже есть, ничего не делает.
     * Возвращает 1 если запись действительно создана, 0 если был дубликат.
     */
    @SqlUpdate(
            """
            INSERT INTO ownership.processed_om_event
                (event_id, entity_type, fqn, occurred_at, payload_sha256)
            VALUES
                (:eventId, :entityType, :fqn, :occurredAt::timestamptz, :payloadSha256)
            ON CONFLICT (event_id) DO NOTHING
            """)
    int recordIfAbsent(
            @Bind("eventId") String eventId,
            @Bind("entityType") String entityType,
            @Bind("fqn") String fqn,
            @Bind("occurredAt") String occurredAtIso,
            @Bind("payloadSha256") String payloadSha256);
}
