-- V014: catalog.code_set.aliases — поддержка rename без потери связи с OM ingestion
-- (E18 §7, обсуждение с om-rdmmesh-source).
--
-- Проблема: ingestion-коннектор om-rdmmesh-source (E12) создаёт OM Table с FQN
-- 'rdmmesh.<domain>.<codeset>'. Если RDM_ADMIN переименовывает codeset (E18.5),
-- коннектор в следующий цикл увидит «новый» CodeSet и создаст вторую Table,
-- оставив старую как orphan.
--
-- Решение: при rename admin может попросить «сохранить alias» — старое имя
-- добавляется в aliases JSONB-массив. Коннектор при создании/обновлении OM Table
-- использует aliases для матчинга в OM по custom property `rdmmesh.aliases` или
-- по имени из массива (точный алгоритм — задача E18.5 на стороне коннектора).
--
-- В долгосрочной перспективе (V2): коннектор должен матчить по стабильному
-- rdmmesh.codeset_id (UUID) в OM custom property, а имя — справочное. Тогда
-- aliases станут не нужны. Сейчас — мост.

ALTER TABLE catalog.code_set
    ADD COLUMN aliases jsonb NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE catalog.code_set
    ADD CONSTRAINT code_set_aliases_is_array CHECK (jsonb_typeof(aliases) = 'array');

CREATE INDEX code_set_aliases_gin_ix
    ON catalog.code_set USING gin (aliases jsonb_path_ops)
    WHERE aliases <> '[]'::jsonb;

COMMENT ON COLUMN catalog.code_set.aliases IS
    'JSONB массив предыдущих имён codeset''а (rename history). Используется ingestion-коннектором (E12) для матчинга OM Table при переименовании. Заполняется PATCH /admin/codesets/{id}:rename при keep_alias_for_ingestion=true.';
