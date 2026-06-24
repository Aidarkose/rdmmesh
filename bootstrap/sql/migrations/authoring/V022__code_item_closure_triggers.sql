-- V022: closure-table обслуживается триггерами вместо полного rebuild через WITH RECURSIVE.
--
-- До V022 после каждого write CodeItem'а AuthoringService вызывал
-- `CodeItemClosureDao.rebuild(versionId)` — рекурсивный CTE по всей версии.
-- Стоимость O(N × depth) на каждый write, O(N²) на bulk-upsert. Для пилотов вроде
-- IFRS9 stages (десятки items) приемлемо; для Security/Access Matrix
-- (Position×System ~ 10⁴–10⁵ rows) — недопустимо.
--
-- E13 round 2 переводит обслуживание на push-семантику через триггеры. Алгоритм —
-- классический closure-table maintenance (Bill Karwin "SQL Antipatterns").
--
-- ── Алгоритм INSERT ──────────────────────────────────────────────────────────
-- При INSERT новой row NEW (key=NEW.key_parts, parent=NEW.parent_key):
--   1. Self-reflexive: (NEW, NEW, 0).
--   2. Ancestors of NEW: если parent_key есть и его closure уже построена —
--      добавить (ancestor → NEW) для каждого ancestor of parent.
--   3. NEW → existing children: если уже есть rows с parent_key=NEW.key (т.е.
--      NEW вставляется ПОСЛЕ своих детей — out-of-order bulk), добавить
--      (NEW → каждый descendant_of_child) с depth + 1.
--   4. Cross-product ancestors × children: связать новые ancestors с
--      существующим поддеревом, depth_ancestor + depth_descendant + 2.
--   Шаги 3-4 гарантируют корректность out-of-order INSERT'ов.
--
-- ── Алгоритм DELETE ──────────────────────────────────────────────────────────
-- При DELETE OLD: удалить все строки closure где ancestor=OLD OR descendant=OLD.
-- Если у OLD были children в code_item — их parent_key теперь висячий, но это
-- application-level concern (parent_key не FK, см. V020 §code_item).
--
-- ── Алгоритм UPDATE OF parent_key (move) ─────────────────────────────────────
-- При UPDATE parent_key (move узла):
--   1. Найти subtree NEW.key — все descendants по closure.
--   2. Удалить все строки (ancestor, descendant) где descendant ∈ subtree И
--      ancestor ∉ subtree — это убирает старые ancestor-связи поддерева.
--   3. Если NEW.parent_key NOT NULL: добавить cross-product
--      new_parent_chain × subtree, depth_anc + depth_des + 1.
--   4. Если NEW.parent_key NULL (move to root) — шаг 3 пропускается, узел
--      становится корневым.
--
-- ── Цикл-protection ──────────────────────────────────────────────────────────
-- Через ON CONFLICT DO NOTHING зацикливание невозможно (повторная вставка
-- (anc, des, *) отбрасывается). Но цикл в parent_key (X → Y → X) создаст
-- (X, X, depth>0) — несогласованность. Старый rebuild ограничивал глубину 32.
-- В триггерах NEW.parent_key = NEW.key_parts блокируем явно через BEFORE-trigger.
-- Глубокие циклы (через несколько узлов) на пилоте не воспроизводятся; при
-- появлении — добавится отдельная проверка-функция и CHECK через trigger.
--
-- ── Backfill ─────────────────────────────────────────────────────────────────
-- Существующие closure-rows могут быть согласованы или нет. На безопасности
-- мы выполним TRUNCATE + rebuild для всех existing версий через старый CTE.
-- Это разовая операция при апгрейде V022.

-- ── 1. BEFORE-trigger на запрет parent_key = key_parts ───────────────────────

CREATE OR REPLACE FUNCTION authoring.code_item_check_self_parent()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.parent_key IS NOT NULL AND NEW.parent_key = NEW.key_parts THEN
        RAISE EXCEPTION 'code_item.parent_key cannot equal key_parts (self-reference): %', NEW.key_parts
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER code_item_check_self_parent_trg
    BEFORE INSERT OR UPDATE OF parent_key ON authoring.code_item
    FOR EACH ROW EXECUTE FUNCTION authoring.code_item_check_self_parent();

-- ── 2. INSERT trigger ────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION authoring.code_item_closure_on_insert()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- Step 1: self-reflexive.
    INSERT INTO authoring.code_item_closure (version_id, ancestor_key, descendant_key, depth)
    VALUES (NEW.version_id, NEW.key_parts, NEW.key_parts, 0)
    ON CONFLICT (version_id, ancestor_key, descendant_key) DO NOTHING;

    -- Step 2: ancestors_of_NEW → NEW (если parent_key есть и его closure уже построена).
    IF NEW.parent_key IS NOT NULL THEN
        INSERT INTO authoring.code_item_closure (version_id, ancestor_key, descendant_key, depth)
        SELECT NEW.version_id, c.ancestor_key, NEW.key_parts, c.depth + 1
          FROM authoring.code_item_closure c
         WHERE c.version_id = NEW.version_id
           AND c.descendant_key = NEW.parent_key
        ON CONFLICT (version_id, ancestor_key, descendant_key) DO NOTHING;
    END IF;

    -- Step 3: NEW → existing_subtree(NEW.key) (для out-of-order INSERT'ов).
    --   Дети, уже находящиеся в code_item с parent_key = NEW.key, могут иметь
    --   собственное поддерево. Связываем NEW со всем поддеревом каждого ребёнка.
    INSERT INTO authoring.code_item_closure (version_id, ancestor_key, descendant_key, depth)
    SELECT NEW.version_id, NEW.key_parts, sub.descendant_key, sub.depth + 1
      FROM authoring.code_item_closure sub
     WHERE sub.version_id = NEW.version_id
       AND sub.ancestor_key IN (
           SELECT child.key_parts
             FROM authoring.code_item child
            WHERE child.version_id = NEW.version_id
              AND child.parent_key = NEW.key_parts)
    ON CONFLICT (version_id, ancestor_key, descendant_key) DO NOTHING;

    -- Step 4: cross-product ancestors_of_NEW × existing_subtree(NEW.key).
    --   Это связывает ancestors NEW с уже-существующими descendants NEW (out-of-order).
    IF NEW.parent_key IS NOT NULL THEN
        INSERT INTO authoring.code_item_closure (version_id, ancestor_key, descendant_key, depth)
        SELECT NEW.version_id, anc.ancestor_key, sub.descendant_key,
               anc.depth + sub.depth + 2
          FROM authoring.code_item_closure anc
         CROSS JOIN authoring.code_item_closure sub
         WHERE anc.version_id = NEW.version_id
           AND anc.descendant_key = NEW.parent_key
           AND sub.version_id = NEW.version_id
           AND sub.ancestor_key IN (
               SELECT child.key_parts
                 FROM authoring.code_item child
                WHERE child.version_id = NEW.version_id
                  AND child.parent_key = NEW.key_parts)
        ON CONFLICT (version_id, ancestor_key, descendant_key) DO NOTHING;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER code_item_closure_insert_trg
    AFTER INSERT ON authoring.code_item
    FOR EACH ROW EXECUTE FUNCTION authoring.code_item_closure_on_insert();

-- ── 3. DELETE trigger ────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION authoring.code_item_closure_on_delete()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    DELETE FROM authoring.code_item_closure
     WHERE version_id = OLD.version_id
       AND (ancestor_key = OLD.key_parts OR descendant_key = OLD.key_parts);
    RETURN OLD;
END;
$$;

CREATE TRIGGER code_item_closure_delete_trg
    AFTER DELETE ON authoring.code_item
    FOR EACH ROW EXECUTE FUNCTION authoring.code_item_closure_on_delete();

-- ── 4. UPDATE OF parent_key (move) trigger ───────────────────────────────────

CREATE OR REPLACE FUNCTION authoring.code_item_closure_on_move()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    -- Если parent_key не изменился — ничего не делаем.
    IF (NEW.parent_key IS NOT DISTINCT FROM OLD.parent_key) THEN
        RETURN NEW;
    END IF;

    -- Step 1: убрать все ancestor-связи поддерева NEW.key_parts извне поддерева.
    --   descendant ∈ subtree(NEW) AND ancestor NOT IN subtree(NEW).
    DELETE FROM authoring.code_item_closure
     WHERE version_id = NEW.version_id
       AND descendant_key IN (
           SELECT descendant_key
             FROM authoring.code_item_closure
            WHERE version_id = NEW.version_id
              AND ancestor_key = NEW.key_parts)
       AND ancestor_key NOT IN (
           SELECT descendant_key
             FROM authoring.code_item_closure
            WHERE version_id = NEW.version_id
              AND ancestor_key = NEW.key_parts);

    -- Step 2: добавить новые ancestor-связи cross-product
    --   new_parent_chain × subtree(NEW). Если новый parent NULL — узел становится корневым.
    IF NEW.parent_key IS NOT NULL THEN
        INSERT INTO authoring.code_item_closure (version_id, ancestor_key, descendant_key, depth)
        SELECT NEW.version_id, anc.ancestor_key, sub.descendant_key,
               anc.depth + sub.depth + 1
          FROM authoring.code_item_closure anc
         CROSS JOIN authoring.code_item_closure sub
         WHERE anc.version_id = NEW.version_id
           AND anc.descendant_key = NEW.parent_key
           AND sub.version_id = NEW.version_id
           AND sub.ancestor_key = NEW.key_parts
        ON CONFLICT (version_id, ancestor_key, descendant_key) DO NOTHING;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER code_item_closure_move_trg
    AFTER UPDATE OF parent_key ON authoring.code_item
    FOR EACH ROW EXECUTE FUNCTION authoring.code_item_closure_on_move();

-- ── 5. Backfill: пересобрать closure для всех существующих версий ───────────
-- После установки триггеров старые записи могут быть рассогласованы (например,
-- если предыдущие rebuild()'ы прошли частично). TRUNCATE + восстановление через
-- старый CTE-алгоритм. Идемпотентно: на свежей БД (closure пустая) — no-op.

DO $$
DECLARE
    v_id uuid;
BEGIN
    -- TRUNCATE безопасен: triggers — AFTER, не INSTEAD OF; на TRUNCATE они не сработают.
    TRUNCATE TABLE authoring.code_item_closure;

    FOR v_id IN
        SELECT DISTINCT version_id FROM authoring.code_item
    LOOP
        INSERT INTO authoring.code_item_closure (version_id, ancestor_key, descendant_key, depth)
        WITH RECURSIVE walk AS (
            SELECT version_id, key_parts AS ancestor_key, key_parts AS descendant_key, 0 AS depth
              FROM authoring.code_item
             WHERE version_id = v_id
            UNION ALL
            SELECT w.version_id, p.key_parts, w.descendant_key, w.depth + 1
              FROM walk w
              JOIN authoring.code_item child
                ON child.version_id = w.version_id
               AND child.key_parts  = w.ancestor_key
              JOIN authoring.code_item p
                ON p.version_id = w.version_id
               AND p.key_parts  = child.parent_key
             WHERE child.parent_key IS NOT NULL
               AND w.depth < 32
        )
        SELECT DISTINCT version_id, ancestor_key, descendant_key, depth FROM walk
        ON CONFLICT (version_id, ancestor_key, descendant_key) DO NOTHING;
    END LOOP;
END$$;
