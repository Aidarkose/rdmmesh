-- V061: ownership — журнал обработанных webhook'ов от OpenMetadata (SPEC §2.4).
--
-- Идемпотентность applyChangeEvent на уровне UNIQUE(asset_id, asset_type, om_user_id, role)
-- срабатывает только когда событие реально что-то меняет в rdm_asset_ownership. Но webhook
-- может быть retry'ем OM на случай сетевых сбоев, или нести события для domain'а
-- (entity_type=domain, который НЕ пишется в ownership.* — он идёт в catalog.domain).
-- Чтобы получатель отвечал 200 на дубликат и не делал лишних UPSERT'ов в catalog/ownership,
-- ведём короткий append-only журнал обработанных event_id.
--
-- Retention: 90 дней (NOT enforced на этой миграции — отдельный VACUUM-job в V14+, или
-- triggered cleanup при росте таблицы. Для пилота 90 дней событий помещаются в десятки МБ).

CREATE TABLE ownership.processed_om_event (
    event_id        text        PRIMARY KEY,
    entity_type     text        NOT NULL CHECK (entity_type IN ('domain', 'table')),
    fqn             text,
    occurred_at     timestamptz,
    received_at     timestamptz NOT NULL DEFAULT now(),
    payload_sha256  text        NOT NULL
);

CREATE INDEX processed_om_event_received_at_ix
    ON ownership.processed_om_event (received_at);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT SELECT, INSERT ON ownership.processed_om_event TO rdmmesh_app';
    END IF;
END$$;
