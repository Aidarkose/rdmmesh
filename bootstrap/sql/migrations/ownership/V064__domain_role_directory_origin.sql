-- V064: ownership.domain_role_directory — расширение source для admin-локальных
-- назначений согласующих (ADR-0010, E18 §2.5).
--
-- До: source CHECK IN ('LOCAL_SEED', 'OM_GENERATED'). Семантика reload —
-- ApproverDirectoryReloadService делает TRUNCATE + INSERT одной транзакцией
-- (full-replace from snapshot, ADR-009, E17).
--
-- После: добавляем source='RDM_ADMIN_LOCAL' для записей, которые admin завёл
-- через UI POST /admin/domains/{id}/approvers. Reload-семантика меняется:
-- теперь это `DELETE WHERE source <> 'RDM_ADMIN_LOCAL'; INSERT ...` — admin-локальные
-- записи переживают full-replace от OM-snapshot'а.
--
-- Контракт downstream-потребителей (UI выбора согласующего, state machine §3.8)
-- не меняется — они читают (domain_id, role, om_user_id) без оглядки на source.
-- Меняется ТОЛЬКО логика reload'а в backend; см. ApproverDirectoryReloadService
-- (изменение приходит вместе с E18 PR'ами).

ALTER TABLE ownership.domain_role_directory
    DROP CONSTRAINT IF EXISTS domain_role_directory_source_check;

-- Имя constraint в Postgres — auto-сгенерированное при CREATE TABLE (V062),
-- обычно table_column_check. Удаляем универсально: ищем все CHECK на source
-- и пересоздаём. Strategy: DROP CONSTRAINT с явным именем + добавляем новый.
-- На случай, если имя отличается — попытка через DO + информационную схему.
DO $$
DECLARE
    cname text;
BEGIN
    SELECT con.conname INTO cname
    FROM pg_constraint con
    JOIN pg_class       cls ON cls.oid = con.conrelid
    JOIN pg_namespace   nsp ON nsp.oid = cls.relnamespace
    WHERE nsp.nspname = 'ownership'
      AND cls.relname = 'domain_role_directory'
      AND con.contype = 'c'
      AND pg_get_constraintdef(con.oid) ILIKE '%source%LOCAL_SEED%';
    IF cname IS NOT NULL THEN
        EXECUTE format('ALTER TABLE ownership.domain_role_directory DROP CONSTRAINT %I', cname);
    END IF;
END$$;

ALTER TABLE ownership.domain_role_directory
    ALTER COLUMN source SET DEFAULT 'LOCAL_SEED';

ALTER TABLE ownership.domain_role_directory
    ADD CONSTRAINT domain_role_directory_source_check
        CHECK (source IN ('LOCAL_SEED', 'OM_GENERATED', 'RDM_ADMIN_LOCAL'));

-- Частичный индекс на admin-локальные — reload-сервис фильтрует
-- WHERE source <> 'RDM_ADMIN_LOCAL', этот индекс ускоряет противоположный
-- запрос (вывод admin'у его собственных назначений).
CREATE INDEX domain_role_directory_admin_local_ix
    ON ownership.domain_role_directory (domain_id, role)
    WHERE source = 'RDM_ADMIN_LOCAL';

COMMENT ON COLUMN ownership.domain_role_directory.source IS
    'Источник ряда: LOCAL_SEED (текущий MVP — локальный сид), OM_GENERATED (будущее — справочник из OM), RDM_ADMIN_LOCAL (admin завёл через UI E18). При reload OM-snapshot''а сервис делает DELETE WHERE source <> ''RDM_ADMIN_LOCAL'' — admin-локальные ряды переживают replace.';
