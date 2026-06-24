-- V072: cryptographic hash-chain для audit.audit_log (E14 round 1).
--
-- SPEC §3.8 Compliance: append-only журнал должен быть tamper-evident. V070
-- зарезервировал колонки prev_hash / entry_hash, V072 наполняет их и переводит
-- AuditService на режим chain-write.
--
-- Алгоритм:
--   canonical_input = coalesce(prev_hash, '') || '|' || event_id::text
--                  || '|' || event_type || '|' || payload_canonical
--                  || '|' || to_char(occurred_at AT TIME ZONE 'UTC',
--                                    'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')
--   entry_hash = encode(digest(canonical_input, 'sha256'), 'hex')
--
-- Канонизация payload'а — отдельная колонка payload_canonical TEXT, в которой
-- лежит byte-stable JSON (sorted keys, no whitespace). Это нужно потому, что
-- Postgres jsonb теряет точные исходные байты (нормализует числа, whitespace,
-- порядок ключей по своему усмотрению), а hash должен пересчитываться
-- 1-в-1 verify-endpoint'ом.
--
-- Изменения миграции:
--   1. ADD COLUMN payload_canonical TEXT (nullable до backfill'а).
--   2. Временный DISABLE TRIGGER audit_log_no_update — чтобы backfill UPDATE'ов
--      работал. ALTER TABLE имеет право это сделать только под owner'ом
--      (rdmmesh_admin); rdmmesh_app по-прежнему ограничен INSERT/SELECT-grants.
--   3. Backfill payload_canonical = payload::text (Postgres сам нормализует;
--      для существующих rows этого достаточно — мы фиксируем то, что уже лежит).
--   4. Backfill hash-chain в id-ASC порядке.
--   5. SET NOT NULL + CHECK на payload_canonical.
--   6. ENABLE TRIGGER обратно.

ALTER TABLE audit.audit_log
    ADD COLUMN payload_canonical TEXT;

ALTER TABLE audit.audit_log DISABLE TRIGGER audit_log_no_update;

-- V070 делает REVOKE UPDATE/DELETE/TRUNCATE с rdmmesh_app, а Flyway ходит в БД
-- именно под rdmmesh_app (он же owner таблицы). В PostgreSQL владелец сохраняет
-- ALTER/DROP-полномочия, но DML-привилегии (UPDATE) у него тоже отзываются
-- REVOKE'ом — поэтому backfill ниже без явного self-GRANT падает на
-- `permission denied for table audit_log`. Скобка GRANT…REVOKE симметрична
-- DISABLE…ENABLE TRIGGER: на время backfill'а возвращаем UPDATE, после —
-- снимаем, восстанавливая append-only-инвариант (SPEC §3.2 #6).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT UPDATE ON audit.audit_log TO rdmmesh_app';
    END IF;
END$$;

-- Шаг 1: заполняем payload_canonical для всех существующих rows.
UPDATE audit.audit_log
   SET payload_canonical = payload::text
 WHERE payload_canonical IS NULL;

-- Шаг 2: backfill hash-chain. Идёт построчно в id-ASC порядке, чтобы prev_hash
-- предыдущей записи был доступен на каждой итерации. Для пилотных объёмов
-- (десятки rows) это микросекунды; для крупного prod это будет один scan по
-- BIGSERIAL индексу.
DO $$
DECLARE
    r       RECORD;
    prev_h  TEXT := NULL;
    curr_h  TEXT;
    canon   TEXT;
BEGIN
    FOR r IN SELECT id, event_id, event_type, payload_canonical, occurred_at
               FROM audit.audit_log
              WHERE entry_hash IS NULL
              ORDER BY id ASC
    LOOP
        canon := coalesce(prev_h, '')
              || '|' || r.event_id::text
              || '|' || r.event_type
              || '|' || r.payload_canonical
              || '|' || to_char(r.occurred_at AT TIME ZONE 'UTC',
                                'YYYY-MM-DD"T"HH24:MI:SS.US"Z"');
        curr_h := encode(public.digest(canon, 'sha256'), 'hex');

        UPDATE audit.audit_log
           SET prev_hash  = prev_h,
               entry_hash = curr_h
         WHERE id = r.id;

        prev_h := curr_h;
    END LOOP;
END$$;

-- После backfill'а — payload_canonical обязателен.
ALTER TABLE audit.audit_log
    ALTER COLUMN payload_canonical SET NOT NULL;

-- Заодно — entry_hash тоже должен быть NOT NULL для всех новых rows. Старые
-- (пред-V072) уже наполнены backfill'ом, новые приходят с заполненным hash'ем
-- из AuditService.
ALTER TABLE audit.audit_log
    ALTER COLUMN entry_hash SET NOT NULL;

-- Снимаем временный self-GRANT — append-only-инвариант восстановлен.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'REVOKE UPDATE ON audit.audit_log FROM rdmmesh_app';
    END IF;
END$$;

ALTER TABLE audit.audit_log ENABLE TRIGGER audit_log_no_update;

-- Идемпотентность миграции: если по какой-то причине V072 запускается на БД,
-- где chain уже частично заполнен (например, point-in-time restore), повторный
-- run UPDATE WHERE entry_hash IS NULL ничего не трогает; SET NOT NULL пройдёт
-- если все строки заполнены.
