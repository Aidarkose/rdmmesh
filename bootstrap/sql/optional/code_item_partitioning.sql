-- ╔══════════════════════════════════════════════════════════════════════════╗
-- ║  OPT-IN ШАБЛОН — НЕ Flyway-миграция. НЕ КЛАСТЬ в bootstrap/sql/migrations. ║
-- ║  Эта папка (bootstrap/sql/optional) НЕ сканируется Flyway'ем              ║
-- ║  (pom копирует только bootstrap/sql/migrations → classpath:db/migration). ║
-- ╚══════════════════════════════════════════════════════════════════════════╝
--
-- LIST-партиционирование authoring.code_item по version_id (SPEC §3.4, E14 r7).
--
-- ── Когда применять ──
-- SPEC §3.4: «Партиционирование code_item по version_id отключено в MVP
-- (объёмы небольшие), но pluggable — добавляется DDL-миграцией без рефакторинга
-- кода». На пилоте НЕ применяется (десятки–сотни справочников × тысячи строк —
-- стандартный OLTP справляется). Применять, когда старые DEPRECATED-версии
-- начинают раздувать БД (SPEC §5.3 риск-таблица) — тогда их строки выносятся в
-- отдельные партиции для дешёвого архива/DROP'а без DELETE.
--
-- ── Почему «без рефакторинга кода» ──
-- DEFAULT-партиция поглощает ВСЕ строки по умолчанию — приложение продолжает
-- INSERT/SELECT'ить authoring.code_item как раньше, не зная о партициях. Ops
-- точечно «отщепляет» конкретные version_id в выделенные партиции функцией
-- authoring.ensure_code_item_partition(version_id) (например, после DEPRECATE).
--
-- ── Применение (под owner-ролью rdmmesh_admin, в maintenance-окне) ──
--   psql -U rdmmesh_admin -d rdmmesh -1 -f code_item_partitioning.sql
-- Идемпотентно: guard по relkind='p' делает повторный прогон no-op. Вся
-- мутация — один DO-блок (одна tx: сбой → полный rollback).
-- Подробности/откат — docs/runbooks/code-item-partitioning.md.

-- ── Функция: отщепить version_id в собственную LIST-партицию ────────────────────
-- Перенос строк из DEFAULT в новую партицию: создаём партицию, Postgres при
-- ATTACH сам валидирует; но строки уже в DEFAULT — поэтому делаем
-- DETACH default → создать партицию → вернуть остаток в default. Для крупных
-- DEPRECATED-версий это разовая ops-операция в maintenance-окне.
CREATE OR REPLACE FUNCTION authoring.ensure_code_item_partition(p_version_id uuid)
RETURNS text LANGUAGE plpgsql AS $fn$
DECLARE
    part text := 'code_item_v' || replace(p_version_id::text, '-', '');
BEGIN
    IF (SELECT c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'authoring' AND c.relname = 'code_item') <> 'p' THEN
        RAISE EXCEPTION 'authoring.code_item не партиционирована — шаблон не применён';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'authoring' AND c.relname = part) THEN
        RETURN part;
    END IF;
    -- DEFAULT нельзя иметь прикреплённым при ATTACH пересекающейся партиции,
    -- если в нём есть строки этого version_id — поэтому detach/​reattach.
    ALTER TABLE authoring.code_item DETACH PARTITION authoring.code_item_default;
    EXECUTE format(
        'CREATE TABLE authoring.%I PARTITION OF authoring.code_item '
        || 'FOR VALUES IN (%L)', part, p_version_id);
    -- Перенести строки этого version_id из бывшего default в новую партицию.
    EXECUTE format(
        'WITH moved AS (DELETE FROM authoring.code_item_default '
        || 'WHERE version_id = %L RETURNING *) '
        || 'INSERT INTO authoring.code_item SELECT * FROM moved', p_version_id);
    ALTER TABLE authoring.code_item
        ATTACH PARTITION authoring.code_item_default DEFAULT;
    RETURN part;
END
$fn$;
COMMENT ON FUNCTION authoring.ensure_code_item_partition(uuid) IS
    'Opt-in (E14 r7): выносит строки одной version_id в отдельную LIST-партицию '
    'для дешёвого архива/DROP. См. docs/runbooks/code-item-partitioning.md.';
REVOKE EXECUTE ON FUNCTION authoring.ensure_code_item_partition(uuid) FROM PUBLIC;

-- ── Структурная мутация: code_item → partitioned (LIST version_id) ──────────────
DO $mig$
DECLARE
    v_rows bigint;
BEGIN
    IF (SELECT c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'authoring' AND c.relname = 'code_item') = 'p' THEN
        RAISE NOTICE 'authoring.code_item уже партиционирована — no-op';
        RETURN;
    END IF;

    -- Новая партиционированная таблица. PK обязан включать ключ партиции:
    -- (id) → (version_id, id). UNIQUE (version_id, key_parts) уже включает его.
    CREATE TABLE authoring.code_item_part (
        id             uuid    NOT NULL DEFAULT gen_random_uuid(),
        version_id     uuid    NOT NULL
                       REFERENCES authoring.code_set_version (id) ON DELETE CASCADE,
        key_parts      jsonb   NOT NULL
            CHECK (jsonb_typeof(key_parts) = 'array'
                   AND jsonb_array_length(key_parts) BETWEEN 1 AND 8),
        parent_key     jsonb,
        parent_ref     jsonb,
        label_ru       text,
        label_en       text,
        description_ru text,
        description_en text,
        attributes     jsonb   NOT NULL DEFAULT '{}'::jsonb,
        order_index    integer NOT NULL DEFAULT 0,
        status         text    NOT NULL DEFAULT 'ACTIVE'
            CHECK (status IN ('ACTIVE', 'RETIRED')),
        effective_from date,
        effective_to   date,
        system_from    timestamptz NOT NULL DEFAULT now(),
        system_to      timestamptz,
        row_version    integer NOT NULL DEFAULT 0,
        PRIMARY KEY (version_id, id),
        UNIQUE (version_id, key_parts)
    ) PARTITION BY LIST (version_id);

    -- DEFAULT — поглощает все строки: приложение не знает о партициях
    -- («без рефакторинга кода»). Ops отщепляет отдельные version_id функцией.
    CREATE TABLE authoring.code_item_default
        PARTITION OF authoring.code_item_part DEFAULT;

    INSERT INTO authoring.code_item_part
        (id, version_id, key_parts, parent_key, parent_ref, label_ru, label_en,
         description_ru, description_en, attributes, order_index, status,
         effective_from, effective_to, system_from, system_to, row_version)
    SELECT id, version_id, key_parts, parent_key, parent_ref, label_ru, label_en,
           description_ru, description_en, attributes, order_index, status,
           effective_from, effective_to, system_from, system_to, row_version
      FROM authoring.code_item;
    GET DIAGNOSTICS v_rows = ROW_COUNT;

    DROP TABLE authoring.code_item;
    ALTER TABLE authoring.code_item_part RENAME TO code_item;

    -- Индексы из V020 (имена сохраняем; на партиционированной таблице это
    -- partitioned-индексы, child'ы создаются автоматически).
    CREATE INDEX code_item_version_keyparts_ix
        ON authoring.code_item (version_id, (key_parts::text));
    CREATE INDEX code_item_attributes_gin_ix
        ON authoring.code_item USING gin (attributes jsonb_path_ops);
    CREATE INDEX code_item_parent_key_gin_ix
        ON authoring.code_item USING gin (parent_key jsonb_path_ops)
        WHERE parent_key IS NOT NULL;
    CREATE INDEX code_item_effective_envelope_ix
        ON authoring.code_item
        USING gist (daterange(effective_from, effective_to, '[)'));
    CREATE INDEX code_item_system_envelope_ix
        ON authoring.code_item
        USING gist (tstzrange(system_from, system_to, '[)'));
    CREATE INDEX code_item_label_ru_trgm_ix
        ON authoring.code_item USING gin (label_ru gin_trgm_ops)
        WHERE label_ru IS NOT NULL;
    CREATE INDEX code_item_label_en_trgm_ix
        ON authoring.code_item USING gin (label_en gin_trgm_ops)
        WHERE label_en IS NOT NULL;
    CREATE INDEX code_item_label_ru_fts_ix
        ON authoring.code_item USING gin (to_tsvector('russian', coalesce(label_ru, '')));
    CREATE INDEX code_item_label_en_fts_ix
        ON authoring.code_item USING gin (to_tsvector('english', coalesce(label_en, '')));

    -- Триггеры V022/V023 (функции — CREATE OR REPLACE, живут; пересоздаём
    -- только привязку к новой таблице; на партиционированной таблице ROW-триггеры
    -- каскадируются в партиции, PG13+).
    CREATE TRIGGER code_item_check_self_parent_trg
        BEFORE INSERT OR UPDATE OF parent_key ON authoring.code_item
        FOR EACH ROW EXECUTE FUNCTION authoring.code_item_check_self_parent();
    CREATE TRIGGER code_item_check_move_no_cycle_trg
        BEFORE UPDATE OF parent_key ON authoring.code_item
        FOR EACH ROW EXECUTE FUNCTION authoring.code_item_check_move_no_cycle();
    CREATE TRIGGER code_item_closure_insert_trg
        AFTER INSERT ON authoring.code_item
        FOR EACH ROW EXECUTE FUNCTION authoring.code_item_closure_on_insert();
    CREATE TRIGGER code_item_closure_delete_trg
        AFTER DELETE ON authoring.code_item
        FOR EACH ROW EXECUTE FUNCTION authoring.code_item_closure_on_delete();
    CREATE TRIGGER code_item_closure_move_trg
        AFTER UPDATE OF parent_key ON authoring.code_item
        FOR EACH ROW EXECUTE FUNCTION authoring.code_item_closure_on_move();

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON authoring.code_item TO rdmmesh_app';
    END IF;

    RAISE NOTICE 'code_item → LIST-partitioned (DEFAULT), перенесено % строк', v_rows;
END
$mig$;
