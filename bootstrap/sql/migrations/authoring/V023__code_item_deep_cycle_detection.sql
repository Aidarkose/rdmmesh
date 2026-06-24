-- V023: deep cycle detection для code_item.parent_key.
--
-- V022 BEFORE-trigger `code_item_check_self_parent` блокирует только direct
-- self-cycle (X.parent_key = X.key_parts). Глубокие циклы — A→B→C→A через
-- последовательные UPDATE'ы — могли оставлять closure в неконсистентном
-- состоянии (см. handoff E13.2 §3 #2, §7 #27).
--
-- E13 round 3 добавляет два уровня защиты:
--
-- 1. BEFORE UPDATE OF parent_key — быстрый check: новый parent не должен
--    быть descendant'ом узла (через closure-lookup). Покрывает типичный
--    UI flow (drag-drop в собственное subtree, manual PATCH).
--
-- 2. AFTER INSERT OR UPDATE OF parent_key — CONSTRAINT TRIGGER
--    INITIALLY DEFERRED — invariant guard. Срабатывает в конце транзакции,
--    проверяет {ancestor=descendant AND depth>0} в closure. Покрывает
--    out-of-order bulk-import циркулярных данных (например A→B и B→A
--    одной транзакцией).
--
-- ── Алгоритм быстрого BEFORE-check ───────────────────────────────────────────
-- closure до операции уже отражает все subtrees. Проверка:
--   "новый parent уже descendant нашего узла"
--   ⇔ EXISTS (closure WHERE ancestor=NEW.key AND descendant=NEW.parent)
-- При TRUE — RAISE EXCEPTION (check_violation), транзакция откатывается.
--
-- ── Алгоритм DEFERRED invariant check ────────────────────────────────────────
-- После любой операции closure должен удовлетворять инварианту:
--   "нет (X, X, depth>0) — depth>0 self-row означает цикл"
-- DEFERRED constraint trigger проверяет это в конце транзакции (после
-- commit'а всех INSERT/UPDATE), один проход. Стоимость — O(N) на проверку.
-- Покрывает edge case'ы, которые BEFORE-trigger не ловит.
--
-- ── Соотношение с V022 ──────────────────────────────────────────────────────
-- V022 `code_item_check_self_parent` остаётся (defense-in-depth для самого
-- частого случая X.parent_key = X.key_parts; быстрая проверка одного поля
-- без обращения к closure).

-- ── 1. BEFORE-trigger: новый parent не должен быть descendant'ом ─────────────

CREATE OR REPLACE FUNCTION authoring.code_item_check_move_no_cycle()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- Только реальный move с не-NULL новым parent. NEW.parent_key = NULL
    -- (move-to-root) циклов не создаёт.
    IF NEW.parent_key IS NOT NULL
       AND NEW.parent_key IS DISTINCT FROM OLD.parent_key THEN
        IF EXISTS (
            SELECT 1
              FROM authoring.code_item_closure
             WHERE version_id     = NEW.version_id
               AND ancestor_key   = NEW.key_parts
               AND descendant_key = NEW.parent_key
        ) THEN
            RAISE EXCEPTION
                'parent_key % is a descendant of % in version % — would create cycle',
                NEW.parent_key, NEW.key_parts, NEW.version_id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER code_item_check_move_no_cycle_trg
    BEFORE UPDATE OF parent_key ON authoring.code_item
    FOR EACH ROW EXECUTE FUNCTION authoring.code_item_check_move_no_cycle();

-- ── 2. DEFERRED CONSTRAINT TRIGGER: invariant ──────────────────────────────
-- Срабатывает в конце транзакции (INITIALLY DEFERRED). Один RAISE при
-- обнаружении любого self-reflexive depth>0 row — это закрытие цикла.

CREATE OR REPLACE FUNCTION authoring.code_item_closure_no_cycle_invariant()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    cycle_row record;
BEGIN
    SELECT ancestor_key, depth
      INTO cycle_row
      FROM authoring.code_item_closure
     WHERE version_id = NEW.version_id
       AND ancestor_key = descendant_key
       AND depth > 0
     LIMIT 1;
    IF FOUND THEN
        RAISE EXCEPTION
            'closure cycle detected in version %: key % is its own ancestor at depth %',
            NEW.version_id, cycle_row.ancestor_key, cycle_row.depth
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER code_item_closure_no_cycle_invariant_trg
    AFTER INSERT OR UPDATE OF parent_key ON authoring.code_item
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION authoring.code_item_closure_no_cycle_invariant();

-- ── 3. Sanity check: backfill после V022 уже выполнил TRUNCATE+rebuild;
--      существующие данные не должны содержать циклов. Если содержат —
--      админ должен запустить disaster-recovery вручную после V023.
DO $$
DECLARE
    bad_count integer;
BEGIN
    SELECT count(*) INTO bad_count
      FROM authoring.code_item_closure
     WHERE ancestor_key = descendant_key AND depth > 0;
    IF bad_count > 0 THEN
        RAISE WARNING
            'V023: existing closure contains % cycle-row(s). Run POST /api/v1/versions/{id}/closure/rebuild for affected versions.',
            bad_count;
    END IF;
END$$;
