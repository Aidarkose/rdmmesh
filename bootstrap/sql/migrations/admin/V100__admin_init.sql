-- V100: schema 'admin' + admin.resolution_task — очередь admin-задач разрешения
-- конфликтов между OM-webhook'ом и RDM-локальным состоянием (ADR-0010, E18 §2.1).
--
-- Эта таблица — НЕ replacement для workflow.approval_task. Они независимы:
--   - workflow.approval_task (V030)  — задачи steward'у/owner'у на согласование
--                                       версий справочников (E5 + BR-21);
--                                       выдача через GET /tasks/my (любой
--                                       authenticated, фильтр по candidate_users).
--   - admin.resolution_task (этот файл) — задачи RDM_ADMIN'у на разрешение
--                                       конфликтов с OM (Domain Linkage,
--                                       Ownership Conflict, Domain Deleted In OM);
--                                       выдача через GET /admin/tasks/my
--                                       (только RDM_ADMIN, единая глобальная очередь).
--
-- В UI обе очереди рендерятся на одной странице «Мои задачи» как два отдельных
-- блока (см. E18 §2.3). Backend контракт /tasks/my НЕ МЕНЯЕТСЯ — admin-tasks
-- живут на отдельном эндпоинте.

CREATE SCHEMA IF NOT EXISTS admin;

CREATE TABLE admin.resolution_task (
    id                  uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    task_type           text        NOT NULL
        CHECK (task_type IN (
            'DOMAIN_LINKAGE',                -- OM прислал ENTITY_CREATED по domain'у,
                                              -- по om_domain_id матча нет, но есть
                                              -- RDM-локальный с похожим именем (E18 §4.A)
            'DOMAIN_DELETED_IN_OM',          -- OM прислал ENTITY_DELETED для LINKED-домена;
                                              -- admin решает: перевести в RDM-local или
                                              -- soft-delete с переносом codeset'ов (§4.D)
            'OWNERSHIP_OM_REMOVAL_CONFLICT'  -- OM убрал ownership-tuple, у нас origin=RDM
                                              -- либо pinned_local=true (§4.C.2)
        )),
    source_event_id     text        NOT NULL UNIQUE,
    related_domain_id   uuid        REFERENCES catalog.domain (id),
    payload             jsonb       NOT NULL,
    status              text        NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'RESOLVED')),
    resolution_action   text
        CHECK (resolution_action IS NULL OR resolution_action IN (
            -- DOMAIN_LINKAGE
            'LINK', 'CREATE_NEW', 'MERGE', 'IGNORE',
            -- DOMAIN_DELETED_IN_OM
            'CONVERT_TO_RDM_LOCAL', 'SOFT_DELETE',
            -- OWNERSHIP_OM_REMOVAL_CONFLICT
            'CONFIRM_REMOVAL', 'KEEP_LOCAL_PIN', 'SWITCH_ORIGIN_TO_OM'
        )),
    resolved_by         uuid,
    resolved_at         timestamptz,
    notes               text,
    created_at          timestamptz NOT NULL DEFAULT now(),

    -- Когда status='RESOLVED', resolved_by/resolved_at/resolution_action обязаны
    -- быть проставлены (атомарный UPDATE в Service-слое). Когда PENDING — наоборот.
    CHECK (
        (status = 'PENDING'  AND resolved_by IS NULL AND resolved_at IS NULL
                             AND resolution_action IS NULL) OR
        (status = 'RESOLVED' AND resolved_by IS NOT NULL AND resolved_at IS NOT NULL
                             AND resolution_action IS NOT NULL)
    )
);

-- Горячий путь GET /admin/tasks/my — admin листает PENDING. Частичный индекс
-- мал (RESOLVED уходит из выборки, и накапливающаяся история не раздувает hot path).
CREATE INDEX resolution_task_pending_ix
    ON admin.resolution_task (created_at DESC)
    WHERE status = 'PENDING';

CREATE INDEX resolution_task_type_ix
    ON admin.resolution_task (task_type, status);

-- Доменный фильтр в UI (Admin → Domain detail → «Pending tasks for this domain»).
CREATE INDEX resolution_task_domain_ix
    ON admin.resolution_task (related_domain_id)
    WHERE related_domain_id IS NOT NULL AND status = 'PENDING';

COMMENT ON TABLE admin.resolution_task IS
    'Очередь задач разрешения конфликтов OM↔RDM для RDM_ADMIN. Не путать с workflow.approval_task (E5/BR-21) — это разные state machines на одном UI-экране «Мои задачи».';
COMMENT ON COLUMN admin.resolution_task.source_event_id IS
    'OM ChangeEvent id (или synthesized id для не-webhook источников). UNIQUE — идемпотентность: повторный webhook не создаёт дубликат задачи.';
COMMENT ON COLUMN admin.resolution_task.payload IS
    'JSONB с контекстом конкретного task_type: для DOMAIN_LINKAGE — OM payload + suggested_local_domain_id + match_score; для OWNERSHIP_OM_REMOVAL_CONFLICT — затронутые tuple''ы (asset_id/role/om_user_id). Формат — контракт между admin-resolver service и UI модал-экраном.';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA admin TO rdmmesh_app';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA admin TO rdmmesh_app';
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA admin GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO rdmmesh_app';
    END IF;
END$$;
