-- V034: workflow — адресная маршрутизация согласования (BR-21, handoff E17).
--
-- 1) version_route: кого Author выбрал согласующими для версии при submit'е.
--    Хранит выбранного STEWARD'а (этап IN_REVIEW → STEWARD_APPROVED) и
--    BUSINESS_OWNER'а (этап STEWARD_APPROVED → OWNER_APPROVED). Нужна, чтобы
--    после steward_approve адресовать OWNER-задачу именно выбранному
--    бизнес-владельцу (а не «вещать» всем asset-owner'ам). Upsert по
--    version_id: повторный submit (после reject) перезаписывает маршрут.
--
-- 2) approval_task.assigned_role: какую directory-роль (STEWARD |
--    BUSINESS_OWNER) представляет адресат задачи — для отображения в
--    «Мои задачи». required_role остаётся техническим ('STEWARD'|'OWNER'),
--    его CHECK не трогаем. NULL = legacy/broadcast (обратная совместимость).

CREATE TABLE workflow.version_route (
    version_id        uuid        PRIMARY KEY,
    domain_id         uuid        NOT NULL,
    codeset_id        uuid        NOT NULL,
    steward_user_id   uuid        NOT NULL,
    owner_user_id     uuid        NOT NULL,
    created_by        uuid        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX version_route_users_ix
    ON workflow.version_route (steward_user_id, owner_user_id);

ALTER TABLE workflow.approval_task
    ADD COLUMN assigned_role text
        CHECK (assigned_role IS NULL OR assigned_role IN ('STEWARD', 'BUSINESS_OWNER'));

-- grants: schema-wide GRANT в V030 уже покрывает новые таблицы через
-- ALTER DEFAULT PRIVILEGES; явный grant для надёжности (на случай если
-- default privileges не применились к этой роли в этом окружении).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE'
              || ' ON workflow.version_route TO rdmmesh_app';
    END IF;
END$$;
