-- V073: RANGE-партиционирование audit.audit_log по occurred_at (E14 round 7).
--
-- SPEC §3.7 — retention audit 7 лет (DEPRECATED published-версии 10 лет).
-- Без партиционирования retention пришлось бы делать DELETE'ом, что:
--   (а) запрещено append-only-триггерами + INSERT-only grant'ом (V070);
--   (б) ломало бы hash-chain (V072) непредсказуемо.
-- Помесячные RANGE-партиции дают retention через DROP целой партиции —
-- быстро, без DELETE, и chain рвётся предсказуемо ровно на границе месяца.
--
-- ── Решение по hash-chain × партиционированию (open question E14 round 1 §3 #3) ──
-- Цепочка остаётся ГЛОБАЛЬНОЙ (по id ASC через все партиции). Партицию НЕЛЬЗЯ
-- дропать внутри retention-окна (это разорвёт верифицируемый сегмент). Дроп
-- разрешён только для партиций ПОЛНОСТЬЮ за пределами retention И только после
-- immutable-архива сегмента (round 10, S3) — это форсит функция
-- audit.drop_audit_partition_if_archived(...). verify-endpoint не меняется:
-- findChainRange ORDER BY id корректно сшивает партиции.
--
-- ── Идемпотентность / безопасность ──
-- Вся структурная мутация — в одном DO-блоке (Flyway оборачивает миграцию в tx:
-- любой сбой → полный rollback, таблица нетронута). Guard по relkind='p' делает
-- повторный прогон (PITR-restore + re-migrate) no-op. Копия строк сохраняет id,
-- occurred_at, payload_canonical, prev_hash, entry_hash байт-в-байт — chain цел.
--
-- ── Изменение схемы (вынуждено правилами партиционирования Postgres) ──
-- Каждый UNIQUE/PK партиционированной таблицы обязан включать ключ партиции:
--   PK     (id)                    → (id, occurred_at)
--   UNIQUE (event_id, event_type)  → (event_id, event_type, occurred_at)   [был V071]
-- Идемпотентность audit-INSERT'а сохраняется: для replay'я одного и того же
-- логического события event_id+event_type+occurred_at стабильны (occurred_at —
-- это event.occurredAt(), не время вставки). AuditLogDao.ON CONFLICT обновлён
-- синхронно. Индекс V071 audit_log_event_id_uq уходит вместе со старой таблицей.

-- ── Maintenance-функция: создать помесячную партицию ────────────────────────────
-- Вызывается ops/cron'ом ЗАРАНЕЕ (текущий + следующий месяц), пока DEFAULT-партиция
-- по этому месяцу пуста — иначе ATTACH конфликтует (см. runbook'е remediation).
CREATE OR REPLACE FUNCTION audit.ensure_audit_partition(p_month date)
RETURNS text LANGUAGE plpgsql AS $fn$
DECLARE
    m_start date := date_trunc('month', p_month)::date;
    m_end   date := (date_trunc('month', p_month) + interval '1 month')::date;
    part    text := format('audit_log_y%sm%s',
                           to_char(m_start, 'YYYY'), to_char(m_start, 'MM'));
BEGIN
    IF (SELECT c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'audit' AND c.relname = 'audit_log') <> 'p' THEN
        RAISE EXCEPTION 'audit.audit_log не партиционирована — V073 не применена?';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
         WHERE n.nspname = 'audit' AND c.relname = part) THEN
        EXECUTE format(
            'CREATE TABLE audit.%I PARTITION OF audit.audit_log '
            || 'FOR VALUES FROM (%L) TO (%L)', part, m_start, m_end);
    END IF;
    RETURN part;
END
$fn$;

COMMENT ON FUNCTION audit.ensure_audit_partition(date) IS
    'E14 round 7: идемпотентно создаёт помесячную партицию audit.audit_log. '
    'Ops/cron вызывает заранее на текущий+следующий месяц (runbook audit-log-retention.md).';

-- ── Guarded retention: дроп партиции только после архива и за retention-окном ────
CREATE OR REPLACE FUNCTION audit.drop_audit_partition_if_archived(
        p_partition  text,
        p_archived   boolean,
        p_retention  interval DEFAULT interval '10 years')
RETURNS void LANGUAGE plpgsql AS $fn$
DECLARE
    bound_to timestamptz;
    bound_expr text;
BEGIN
    IF NOT p_archived THEN
        RAISE EXCEPTION
            'audit-партицию % можно дропать только после immutable-архива '
            '(p_archived=false): hash-chain требует, чтобы сегмент был сначала '
            'заархивирован (round 10, S3)', p_partition
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    SELECT pg_get_expr(c.relpartbound, c.oid)
      INTO bound_expr
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'audit' AND c.relname = p_partition;
    IF bound_expr IS NULL THEN
        RAISE EXCEPTION 'партиция audit.% не найдена', p_partition;
    END IF;
    IF bound_expr = 'DEFAULT' THEN
        RAISE EXCEPTION 'DEFAULT-партицию дропать нельзя (это safety-net)';
    END IF;
    bound_to := (regexp_match(bound_expr, 'TO \(''([^'']+)''\)'))[1]::timestamptz;
    IF bound_to IS NULL THEN
        RAISE EXCEPTION 'не удалось извлечь верхнюю границу партиции % (%)',
            p_partition, bound_expr;
    END IF;
    IF bound_to > now() - p_retention THEN
        RAISE EXCEPTION
            'audit-партиция % (верхняя граница %) внутри retention-окна % — '
            'дроп запрещён (разорвёт верифицируемый сегмент hash-chain)',
            p_partition, bound_to, p_retention
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    EXECUTE format('DROP TABLE audit.%I', p_partition);
    RAISE NOTICE 'audit-партиция % дропнута (архив подтверждён, вне retention)',
        p_partition;
END
$fn$;

COMMENT ON FUNCTION audit.drop_audit_partition_if_archived(text, boolean, interval) IS
    'E14 round 7: единственный легальный путь удаления audit-партиции. Отказывает, '
    'если не подтверждён архив либо граница внутри retention. Hash-chain остаётся '
    'глобальной; дропнутый сегмент восстанавливается из S3-архива (round 10).';

-- Ops-функции — только под owner-ролью. PUBLIC по умолчанию получает EXECUTE.
REVOKE EXECUTE ON FUNCTION audit.ensure_audit_partition(date) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION
    audit.drop_audit_partition_if_archived(text, boolean, interval) FROM PUBLIC;

-- ── Структурная мутация: rebuild → partitioned, swap ────────────────────────────
DO $mig$
DECLARE
    v_min   date;
    v_max   date;
    v_cur   date;
    v_end   date;
    v_part  text;
    v_rows  bigint;
BEGIN
    -- Guard: уже партиционирована (повторный прогон / PITR) → no-op.
    IF (SELECT c.relkind FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
          WHERE n.nspname = 'audit' AND c.relname = 'audit_log') = 'p' THEN
        RAISE NOTICE 'audit.audit_log уже партиционирована — V073 no-op';
        RETURN;
    END IF;

    -- 1. Новая партиционированная таблица. id — bigint поверх существующей
    --    sequence (бывший bigserial). PK/UNIQUE расширены ключом партиции.
    CREATE TABLE audit.audit_log_part (
        id                bigint      NOT NULL
                          DEFAULT nextval('audit.audit_log_id_seq'::regclass),
        event_id          uuid        NOT NULL,
        event_type        text        NOT NULL,
        aggregate_type    text,
        aggregate_id      uuid,
        actor             uuid,
        occurred_at       timestamptz NOT NULL DEFAULT now(),
        payload           jsonb       NOT NULL,
        payload_canonical text        NOT NULL,
        prev_hash         text
            CHECK (prev_hash IS NULL OR prev_hash ~ '^[a-f0-9]{64}$'),
        entry_hash        text        NOT NULL
            CHECK (entry_hash ~ '^[a-f0-9]{64}$'),
        PRIMARY KEY (id, occurred_at),
        UNIQUE (event_id, event_type, occurred_at)
    ) PARTITION BY RANGE (occurred_at);

    -- 2. DEFAULT-партиция — safety-net: INSERT никогда не падает из-за
    --    отсутствующей помесячной партиции (audit best-effort, но пропуск
    --    строки рвёт chain — недопустимо). В steady-state пуста, т.к. cron
    --    создаёт помесячные партиции заранее.
    CREATE TABLE audit.audit_log_default
        PARTITION OF audit.audit_log_part DEFAULT;

    -- 3. Помесячные партиции на весь диапазон данных + следующий месяц.
    --    DEFAULT пуст (данные ещё не скопированы) → ATTACH не конфликтует.
    SELECT date_trunc('month', COALESCE(min(occurred_at), now()))::date,
           date_trunc('month',
               GREATEST(COALESCE(max(occurred_at), now()), now()))::date
      INTO v_min, v_max
      FROM audit.audit_log;

    v_cur := v_min;
    WHILE v_cur <= (v_max + interval '1 month')::date LOOP
        v_end  := (v_cur + interval '1 month')::date;
        v_part := format('audit_log_y%sm%s',
                         to_char(v_cur, 'YYYY'), to_char(v_cur, 'MM'));
        EXECUTE format(
            'CREATE TABLE audit.%I PARTITION OF audit.audit_log_part '
            || 'FOR VALUES FROM (%L) TO (%L)', v_part, v_cur, v_end);
        v_cur := v_end;
    END LOOP;

    -- 4. Копия строк. id/occurred_at/hash'и — байт-в-байт (chain цел).
    --    ORDER BY id — для детерминизма дампов; на stored hash не влияет.
    INSERT INTO audit.audit_log_part
        (id, event_id, event_type, aggregate_type, aggregate_id, actor,
         occurred_at, payload, payload_canonical, prev_hash, entry_hash)
    SELECT id, event_id, event_type, aggregate_type, aggregate_id, actor,
           occurred_at, payload, payload_canonical, prev_hash, entry_hash
      FROM audit.audit_log
     ORDER BY id;
    GET DIAGNOSTICS v_rows = ROW_COUNT;

    -- 5. Sequence: следующий nextval = max(id)+1. Если строк нет — оставляем.
    PERFORM setval('audit.audit_log_id_seq',
        COALESCE((SELECT max(id) FROM audit.audit_log_part),
                 (SELECT last_value FROM audit.audit_log_id_seq)), true);

    -- 6. Swap. Отвязать sequence от старой таблицы, иначе DROP уронит и её.
    ALTER SEQUENCE audit.audit_log_id_seq OWNED BY NONE;
    DROP TABLE audit.audit_log;
    ALTER TABLE audit.audit_log_part RENAME TO audit_log;
    ALTER SEQUENCE audit.audit_log_id_seq OWNED BY audit.audit_log.id;

    -- 7. Вторичные индексы (имена из V070; уходили со старой таблицей).
    --    На партиционированной таблице это partitioned-индексы (child-индексы
    --    создаются автоматически для существующих и будущих партиций).
    CREATE INDEX audit_log_aggregate_ix
        ON audit.audit_log (aggregate_type, aggregate_id, occurred_at DESC);
    CREATE INDEX audit_log_actor_ix
        ON audit.audit_log (actor, occurred_at DESC);
    CREATE INDEX audit_log_event_type_ix
        ON audit.audit_log (event_type, occurred_at DESC);
    CREATE INDEX audit_log_payload_gin_ix
        ON audit.audit_log USING gin (payload jsonb_path_ops);

    -- 8. Append-only триггеры (функция audit.audit_log_no_modify из V070 жива).
    --    STATEMENT-триггеры на партиционированном родителе срабатывают на
    --    операциях по родителю; прямой DML по партиции в обход родителя
    --    отсекается REVOKE'ом роли (defense-in-depth, как в V070).
    CREATE TRIGGER audit_log_no_update
        BEFORE UPDATE ON audit.audit_log
        FOR EACH STATEMENT EXECUTE FUNCTION audit.audit_log_no_modify();
    CREATE TRIGGER audit_log_no_delete
        BEFORE DELETE ON audit.audit_log
        FOR EACH STATEMENT EXECUTE FUNCTION audit.audit_log_no_modify();
    CREATE TRIGGER audit_log_no_truncate
        BEFORE TRUNCATE ON audit.audit_log
        FOR EACH STATEMENT EXECUTE FUNCTION audit.audit_log_no_modify();

    -- 9. Grants — те же, что V070: app-роль только SELECT/INSERT (+ sequence).
    --    DML маршрутизируется через родителя → проверяется привилегия родителя;
    --    отдельные grant'ы на партиции не нужны.
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA audit TO rdmmesh_app';
        EXECUTE 'GRANT SELECT, INSERT ON audit.audit_log TO rdmmesh_app';
        EXECUTE 'GRANT USAGE ON SEQUENCE audit.audit_log_id_seq TO rdmmesh_app';
        EXECUTE 'REVOKE UPDATE, DELETE, TRUNCATE ON audit.audit_log FROM rdmmesh_app';
    END IF;

    RAISE NOTICE 'V073: audit.audit_log → RANGE-partitioned, перенесено % строк', v_rows;
END
$mig$;
