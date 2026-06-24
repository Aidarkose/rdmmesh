-- V026: статус синхронизации rd_data после публикации версии (Stage 7, B+A).
--
-- После Stage 7c источник истины — rd_data, а не code_item. Пересборка __current
-- на publish'е раньше была best-effort и при конфликте (напр. нарушение
-- материализованного FK между __current-таблицами) падала МОЛЧА: authoring
-- помечал версию PUBLISHED, а rd_data оставался на прежней версии.
--
-- Эта таблица делает результат пересборки видимым:
--   OK      — __current успешно пересобран под эту версию;
--   STALE   — публикация прошла, но пост-коммитная пересборка упала (rd_data отстал);
--   BLOCKED — пред-проверка перед публикацией показала, что пересборка невозможна,
--             поэтому публикация была отклонена (версия осталась OWNER_APPROVED).
-- reason — человекочитаемая причина (текст ошибки PG, напр. FK violation).

CREATE TABLE authoring.relational_sync_status (
    version_id  uuid        PRIMARY KEY,
    codeset_id  uuid        NOT NULL,
    state       text        NOT NULL CHECK (state IN ('OK', 'STALE', 'BLOCKED')),
    reason      text,
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX relational_sync_status_codeset_ix
    ON authoring.relational_sync_status (codeset_id);
