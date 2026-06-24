# E25 — Cross-CodeSet references (FK-связи справочников)

## TL;DR

Справочник (CodeSet) может объявлять **FK-связи** своих колонок с колонками
других справочников — в т.ч. из других доменов. Связи отдаются в OpenMetadata
как `FOREIGN_KEY` `tableConstraints`, так что в каталоге БД видна связь колонок,
связанные справочники соединяются и пользователь навигирует между ними.

Связь = `{ from_column, to_codeset_id, to_column }`, где `from_column` — имя
key-part'а или атрибута ЭТОГО справочника, а `to_codeset_id` + `to_column` —
колонка целевого. Backend referential integrity **не** проверяет (как E20 —
descriptive metadata, graceful degradation).

Пример (данные заказчика):
`r_branch.r_lnk_branch_to_ecl_sgmnt.branch_sgmnt_id → r_branch.r_ecl_branch_sgmnt.id`,
`r_coefs.r_coef_pd.branch_id → r_branch.r_ecl_branch_sgmnt.id` (cross-domain).

## Что в слайсе

| Часть | Где | Что делает |
|------|-----|-----------|
| Schema | `rdmmesh-spec/schema/entity/code-set.json` | `CodeSet.references: CodeSetRef[]` (`from_column`, `to_codeset_id`, `to_column`, `label?`) |
| DB | `bootstrap/sql/migrations/catalog/V016__codeset_references.sql` | `catalog.code_set.column_refs jsonb DEFAULT '[]'` (`references` — reserved word) |
| Backend DAO | `CodeSetDao` | чтение `column_refs::text`, `updateReferences(id, json)` |
| Backend mapper | `CatalogMappers.toCodeSet` | парсит `column_refs` → `List<CodeSetRef>` |
| Backend service | `CatalogService.setReferences` | полная замена набора в одной транзакции |
| Backend REST | `CodeSetResource` | `PUT /api/v1/codesets/{id}/references` (Author/Schema Designer/Admin), `GET` отдаёт `references` |
| OM source | `om-rdmmesh-source/.../models.py`, `mapping.py`, `metadata.py` | `references` → FOREIGN_KEY `tableConstraints` с резолвом `to_codeset_id` → FQN целевой таблицы (кросс-домен) |
| UI types/api | `rdmmesh-ui/src/api/types.ts`, `endpoints.ts` | `CodeSetRef`, `apiMutations.setCodeSetReferences` |
| UI panel | `rdmmesh-ui/src/components/CodeSetReferencesPanel.tsx` (+ `CodeSetPage.tsx`) | просмотр связей со ссылкой на целевой справочник + каскадный пикер (домен→справочник→колонка) для добавления |
| Seed | `scripts/seed-references.sh` | резолв имён→id и PUT перечисленных заказчиком связей, идемпотентно, skip отсутствующих |

## Контракт `PUT /codesets/{id}/references`

```json
{ "references": [
  { "from_column": "branch_sgmnt_id", "to_codeset_id": "<uuid>", "to_column": "id" }
] }
```

PUT-семантика — полная замена. Пустой/отсутствующий массив очищает связи.
`from_column`/`to_column` валидируются как lower snake_case (`^[a-z][a-z0-9_]{0,63}$`).

## Как связь попадает в OpenMetadata

`metadata.py::_build_table_constraints` для каждого `CodeSet.references[]`:
1. `_resolve_fk_table_fqn(to_codeset_id)` — берёт целевой CodeSet из кеша (или
   доп. `GET /codesets/{id}` для целей из ещё не пройденных доменов), домен по
   `domain_id` (индекс заполняется целиком в `get_database_schema_names`),
   строит FQN таблицы `service.default.<domain>.<codeset>`.
2. referred column FQN = `<table_fqn>.<to_column>`.
3. `TableConstraint(constraintType=FOREIGN_KEY, columns=[from_column], referredColumns=[...])`.
Если цель не резолвится (удалена/не найдена) — ref тихо пропускается.

Pure-логика (`build_fk_constraint_specs`, `build_column_fqn`) покрыта тестами в
`tests/test_mapping_pure.py` (без OM SDK).

## Что НЕ в слайсе

- **Backend-валидация** существования `to_codeset_id`/колонок — связь
  descriptive, проверки нет (аналогично E20 Slice A).
- **Composite FK** одним констрейнтом: каждая связь → отдельный `FOREIGN_KEY`.
  Для составного ключа цели заводите несколько связей.
- **Обратная навигация** (кто ссылается на меня) — только прямые связи; в OM
  обратную сторону рисует сам каталог по lineage/constraints.
- **OM column-lineage**: только constraints, не lineage-edges.

## Smoke

1. Поднять стенд + засеять справочники доменов r_product/r_branch/r_pledge/r_coefs.
2. `./scripts/seed-references.sh` (или вручную через UI: страница справочника →
   карточка «Связи со справочниками» → «Добавить связь»).
3. UI: на странице справочника видны связи со ссылкой на целевой справочник.
4. Прогнать ingestion `om-rdmmesh-source` → в OpenMetadata на таблице справочника
   вкладка constraints показывает FOREIGN_KEY на связанную таблицу.
