-- Stage 7e (спайк relational-codesets): полный снос JSONB generic-пути.
--
-- К этому моменту write/read/publish/distribution переключены на rd_data
-- (Stage 7a–7d): authoring пишет items в rd_data."<base>__draft", publish
-- материализует __current/__history, distribution и canonical/content_hash
-- читают из rd_data. Таблицы code_item / code_item_closure больше не нужны.
--
-- НЕ трогаем catalog.code_set_schema.json_schema — из него выводятся колонки
-- физических таблиц (RelationalStoreService) и идёт валидация attributes.
--
-- ВНИМАНИЕ: необратимо. Применять только после зелёного CI (Testcontainers ITs),
-- подтвердившего боевую работу rd_data write/read/publish/distribution.

DROP TABLE IF EXISTS authoring.code_item_closure CASCADE;
DROP TABLE IF EXISTS authoring.code_item CASCADE;

-- Trigger-функции closure / cycle-detection (V022/V023) — триггеры ушли вместе
-- с таблицей, удаляем осиротевшие функции.
DROP FUNCTION IF EXISTS authoring.code_item_closure_on_insert() CASCADE;
DROP FUNCTION IF EXISTS authoring.code_item_closure_on_delete() CASCADE;
DROP FUNCTION IF EXISTS authoring.code_item_closure_on_move() CASCADE;
DROP FUNCTION IF EXISTS authoring.code_item_check_self_parent() CASCADE;
DROP FUNCTION IF EXISTS authoring.code_item_check_move_no_cycle() CASCADE;
DROP FUNCTION IF EXISTS authoring.code_item_closure_no_cycle_invariant() CASCADE;
