# Spike — Relational CodeSets (отказ от JSONB)

## Идея

Вместо generic-хранилища с JSONB (`authoring.code_item.key_parts`/`attributes`,
`catalog.code_set_schema.json_schema`) каждый справочник материализуется в
**реальную типизированную таблицу** в схеме `rd_data`: по колонке на каждую
key-part и на каждый атрибут, строки — настоящие строки «ячейка за ячейкой».

Это ветка `spike/relational-codesets`. Решение по объёму — **полная замена
storage** (см. вопрос заказчику), безопасно делается стадиями. Модель версионности —
**вариант C**: на справочник две таблицы (draft + current).

## Модель версионности (вариант C)

На справочник — две физические таблицы в `rd_data`, производные от базового имени
`<domain>__<codeset>` (≤54 символов, чтобы суффиксы укладывались в лимит идентификатора PG 63):

- **`"<base>__draft"`** — рабочая область авторинга. PK `(version_id, <ключи>)`; все
  черновики сосуществуют строками, различаются по `version_id`.
- **`"<base>__current"`** — текущий PUBLISHED-снапшот. PK `(<ключи>)` — то, на что
  можно вешать **настоящие FK** (Stage 6) и что отдаётся потребителям/distribution.

Write-path пишет в `__draft` по `version_id`; на **publish** `__current` атомарно
пересобирается из draft нужной версии (`DELETE` + `INSERT ... SELECT`).

## Что сделано (Stage 1 + Stage 2)

| Часть | Где |
|------|-----|
| Миграция | `V024__relational_store.sql` — `rd_data` + grants(`CREATE`) + реестр `codeset_physical_table` (base name + `published_version_id`) |
| Порт | `CatalogReadPort.CodeSetSnapshot.keySpecJson` (+ `CatalogReadAdapter`) |
| Типы | `internal/relational/RelationalTypes` — key-part / JSON Schema → SQL-тип (pure) |
| DDL | `internal/relational/RelationalDdlBuilder` — base/draft/current имена, `createTableWithPk`, `withStandard`, валидация идентификаторов (pure) |
| Сервис | `internal/relational/RelationalStoreService` — `provision` (draft+current), `upsertDraftRow`/`deleteDraftRow`, `syncFromVersion` (бэкфилл code_item→draft), `publish` (пересборка current), `listCurrentRows`/`listDraftRows` |
| DAO | `internal/dao/PhysicalTableRegistryDao` (+ `setPublishedVersion`) |
| REST | `resource/RelationalCodeSetResource` — `provision`, `sync`, `draft-rows` (POST/DELETE/GET), `publish`, `rows` (current) |
| Тест | `RelationalDdlBuilderTest` (типы + DDL + draft/current, pure) |

### Маппинг типов

key-part: STRING→text, INTEGER→bigint, NUMBER→double precision, BOOLEAN→boolean,
DATE→date, DATETIME→timestamptz, UUID→uuid.
JSON Schema property: то же + enum→text, object/array→jsonb, string+format(date/
date-time/uuid)→date/timestamptz/uuid.

### Форма таблиц

```sql
CREATE TABLE rd_data."<base>__draft" (
    "version_id" uuid NOT NULL,
    "<keypart>"  <type> NOT NULL, ...           -- по одной на key-part
    "<attr>"     <type>, ...                     -- по одной на атрибут
    "label_ru" text, "label_en" text,
    "status" text NOT NULL DEFAULT 'ACTIVE',
    "effective_from" date, "effective_to" date,
    PRIMARY KEY ("version_id", "<keypart>", ...)
);
CREATE TABLE rd_data."<base>__current" ( /* те же колонки без version_id */
    PRIMARY KEY ("<keypart>", ...)               -- цель настоящих FK (Stage 6)
);
```

Безопасность: имена идентификаторов валидируются snake_case-паттерном, значения
биндятся параметрами с `CAST(:p AS <type>)` — конкатенации значений в SQL нет.

## Что сделано (Stage 2-final — relational driven нормальным flow'ом)

Цель стадии: завязать обычный authoring-flow на relational store, чтобы
`__draft`/`__current` наполнялись без ручных `/relational/...` вызовов.

| Часть | Где |
|------|-----|
| Live draft-mirror | `RelationalStoreService.mirrorUpsertItem`/`mirrorDeleteItem`/`mirrorClearDraft` — точечное зеркало item'а в `__draft` (lazy provision через `ensureProvisioned`, ключи позиционно из `keyParts`) |
| Dual-write | `AuthoringService` получил nullable `RelationalStoreService`; `addItem`/`updateItem` зеркалят из готового `CodeItemDto`, `deleteItem` — по `keyParts`, `clearAllItems` — clear, bulk — `syncFromVersion` (upsert-only пачка). Всё **best-effort** в отдельной tx через `mirror(...)` — сбой зеркала не роняет `code_item` |
| Publish-хук | `RelationalStoreService.registerOn(EventBus)` + `onVersionPublished` — подписка на `VersionPublishedDomainEvent` (тот же bus, что у publishing). После реального publish'а (E6) post-commit пересобирает `__current`: `syncFromVersion` (бэкфилл draft из `code_item`) + `publish`. Best-effort (SPEC §3.8) |
| Wiring | `AuthoringModule.build` создаёт один `RelationalStoreService`, регистрирует на `eventBus` и отдаёт в `AuthoringService` |
| Тест | `RelationalStoreServiceTest` — pure-гарантии, что `onVersionPublished` никогда не бросает (null/blank/bad uuid/backend-error) |

**Ключевое решение — почему через `EventBus`, а не внутрь publish-tx.** `PublishingService`
эмитит `VersionPublishedDomainEvent` уже **после** commit'а основной tx. Реляционная
пересборка идёт отдельной tx, best-effort — так сбой/баг зеркала не может зароллбэчить
реальный publish (важно: локально ITs не гоняются, цена регрессии высока). `code_item`
остаётся источником истины и на publish'е заново «кормит» relational через `syncFromVersion`,
поэтому любое расхождение live-зеркала самоисцеляется на publish'е.

## Что сделано (Stage 4-lite — недостающие колонки под read-path)

Перед read-path'ом (Stage 3) дорастили обе таблицы недостающими полями `code_item`:

- `RelationalDdlBuilder.STANDARD` += `description_ru`/`description_en` (text),
  `parent_key` (**jsonb**-массив key-part'ов, без self-FK — это Stage 6), `order_index` (integer).
- `RelationalDdlBuilder.addColumnsIfNotExists` — идемпотентный `ALTER TABLE … ADD COLUMN
  IF NOT EXISTS` (без NOT NULL — упал бы на непустой таблице; DEFAULT переносится).
  `provision` зовёт его для обеих таблиц → эволюция схемы без ручного drop.
- `RelationalStoreService`: `syncFromVersion`-SELECT + `ItemRow` + `toCells` и
  `mirrorUpsertItem`/`itemCells` тянут новые поля; `AuthoringService.mirrorUpsert` отдаёт их из DTO.
- `publish` (INSERT…SELECT по `dataColumns`) подхватывает новые колонки без правок.

Чего пока НЕТ (остаётся в Stage 4-full): bitemporal `system_from`/`system_to`, closure/
cycle-detection, `parent_ref`, per-row `content_hash`. `__current` по-прежнему = один
последний PUBLISHED-снапшот (без произвольных semver/`knowledge_as_of`).

## Что сделано (Stage 3 — read-path → CodeItemDto)

Проекция строк `rd_data` обратно в канонический `CodeItemDto` (динамический SELECT → DTO) —
доказательство, что relational store воспроизводит API-контракт `code_item` целиком.

- `RelationalStoreService.projectRow` — **pure static** (column→value map → `CodeItemDto`):
  key-колонки → `key_parts`; не-ключевые/не-стандартные → `attributes`; `parent_key`
  парсится из jsonb-текста; `id`/`system_*`/`row_version`/`parent_ref` = null (нет в модели).
  Тестируется без БД (`RelationalStoreServiceTest.projectRow_*`).
- `RelationalDdlBuilder.standardColumnNames()` — единый источник имён стандартных колонок
  (классификация attribute vs standard в проекции).
- `listCurrentItems(codesetId)` / `listDraftItems(versionId)` — SELECT * + `ORDER BY
  order_index NULLS LAST, <ключи>` → `projectRow`.
- REST (read-only, additive — рядом с `code_item`, не заменяя): `GET …/items`
  (PUBLISHED-снапшот), `GET …/draft-items?version_id=` (черновик).

**Почему additive, а не hard-switch `CodeItemResource`/distribution:** relational store
пока best-effort зеркало (source-of-truth — `code_item`); жёсткое переключение
production-чтения = риск отдать неполные данные при пропуске зеркала. Эндпоинты `/items`
доказывают round-trip rd_data → канонический DTO; переключение основного чтения — Stage 7.
Ограничение проекции: jsonb-атрибуты (object/array) приходят как JSON-текст.

## Что сделано (Stage 4-full — bitemporal + иерархия)

**Stage 4a — bitemporal + parent_ref.** `STANDARD` += `system_from`/`system_to`
(timestamptz, зеркало `code_item.system_*`) и `parent_ref` (jsonb, cross-codeset ссылка).
Sync/mirror/projection тянут их; `projectRow` отдаёт `system_*` строками и `parent_ref`
как Map (`parseMap`). Теперь `__draft`/`__current` несут **весь** набор полей `code_item`.

**Stage 4b — closure + cycle-detection (on-demand, без материализации).** В
`RelationalDdlBuilder` — чистые генераторы рекурсивного SQL (тестируются без БД):

- `closureQuery` — `WITH RECURSIVE` по `parent_key`: пары `(ancestor_key, descendant_key,
  depth)`, self-reflexive + walk вверх, лимит глубины 32 (как триггерный V022).
  `self_key = jsonb_build_array(<ключевые колонки>)` — та же форма, что в `parent_key`
  ребёнка, поэтому join сравнивает jsonb напрямую.
- `cycleDetectionQuery` — нативный `CYCLE self_key SET is_cycle USING path` (PG 14+):
  ключи, участвующие в цикле (аналог триггерной проверки V023, on-demand).
- `RelationalStoreService.currentClosure`/`draftClosure`/`currentCycles`/`draftCycles`
  исполняют их и парсят jsonb-ключи в `List<String>` (`ClosureRow`).
- REST (read-only): `GET …/closure[?version_id=]`, `GET …/cycles[?version_id=]`
  (без `version_id` — по `__current`, с ним — по `__draft` версии).

Отличие от `code_item`: closure **не материализуется** (нет per-table closure-таблицы +
триггеров) — вычисляется запросом. Для спайка это проще и достаточно; материализация —
если упрётся в производительность. Рекурсивный SQL локально не прогоняется (нет БД) —
покрыт pure-тестами на структуру, исполнение валидирует CI.

Чего всё ещё НЕТ: per-row `content_hash`/детерминированная сериализация и колоночный
`DiffCalculator` — это **Stage 5**.

## Что сделано (Stage 5 — content_hash + колоночный diff)

**Канонизация вынесена в общий хелпер** `internal/CanonicalSnapshot` (pure): порядок полей
`KEYS`, сортировка items по компактному `key_parts`, сериализация каноническим маппером
(`ORDER_MAP_ENTRIES_BY_KEYS` → алфавитный порядок ключей), `sha256Hex`. `PublishedSnapshotAdapter`
отрефакторен на него (байты не изменились — тот же алгоритм). Теперь и `code_item`-путь,
и relational используют **один** канонизатор → `content_hash` совпадает по построению при
равенстве данных.

- `RelationalStoreService.currentContentHash`/`draftContentHash` — собирают canonical-items
  из строк `rd_data` (`canonicalItemFromRow`: ключи стрингуются, jsonb-атрибуты парсятся
  обратно в объект, null-атрибуты опускаются) и хэшируют через `CanonicalSnapshot`. version_id
  для `__current` берётся из реестра (`published_version_id`).
- `RelationalStoreService.diff(from, to)` + pure-ядро `diffRows` — колоночный diff двух версий
  по `__draft`: ADDED/REMOVED/CHANGED по ключу + список изменённых колонок. Из сравнения
  исключены `version_id` и `system_*` (per-insert now()).
- REST (read-only): `GET …/content-hash[?version_id=]`, `GET …/diff?from_version_id=&to_version_id=`.
- Pure-тесты: `CanonicalSnapshotTest` (детерминизм + Integer/Long-парити + code_item↔relational
  parity), `diffRows_*`.

Parity-замечание: байтовое совпадение хэшей гарантируется общим канонизатором при равенстве
полей; per-item маппинг (стрингование ключей, реконструкция attributes из колонок) выровнен
и покрыт parity-тестом, исполнение на реальной БД валидирует CI.

## Что сделано (Stage 6 — настоящие FK между __current)

E25 `column_refs` (cross-codeset связи) → реальные enforced `FOREIGN KEY` между
`rd_data."<base>__current"`-таблицами.

- Порт: `CatalogReadPort.referencesOf(codesetId)` → `List<CodeSetReferenceSnapshot>`
  (`fromColumn`, `toCodesetId`, `toColumn`); реализация в `CatalogReadAdapter` парсит
  `catalog.code_set.column_refs` (битый JSON не роняет — E25 descriptive).
- DDL (pure): `RelationalDdlBuilder.addForeignKey` — идемпотентный `ALTER TABLE … DROP
  CONSTRAINT IF EXISTS …, ADD CONSTRAINT … FOREIGN KEY(from) REFERENCES …(to)` (drop+add
  в одном statement); `foreignKeyName` — имя с хеш-суффиксом при превышении 63.
- `RelationalStoreService.applyForeignKeys(codesetId)` — по каждой связи **defensive**:
  применяет FK только если `from_column` есть в таблице, целевой справочник provisioned
  и `to_column` — единственная PK-колонка целевого `__current` (Postgres FK требует
  unique на цели; составной PK одной колонкой не покрыть). Остальное — `SkippedFk` с
  причиной (graceful, как E25). Возвращает `ForeignKeyReport(applied, skipped)`.
- REST: `POST …/foreign-keys` (Author/Schema Designer/Admin).
- Pure-тесты: `foreignKeyName`/`addForeignKey` (структура + валидация идентификаторов).

**Self-FK на `parent_key` НЕ сделан (осознанно).** `parent_key` хранится как `jsonb`-массив,
а настоящий self-FK требует, чтобы родительские поля были теми же типизированными колонками,
что и ключ (т.е. отдельные `parent_<keypart>`-колонки). Это структурное изменение модели;
для иерархии уже есть on-demand closure/cycle-detection (Stage 4b). Materialized parent-columns
+ self-FK — отдельный шаг, если потребуется БД-enforced ссылочная целостность иерархии.

## Как потрогать

```bash
make up        # Flyway применит V024 (rd_data + реестр)
T=$(make kc-token)
CS=<codeset-uuid>;  V=<draft-version-uuid>
# 1) создать обе таблицы (draft + current) из key_spec + схемы
curl -X POST -H "Authorization: Bearer $T" localhost:8080/api/v1/relational/codesets/$CS/provision
# 2a) залить существующие items версии в draft (бэкфилл из jsonb)
curl -X POST -H "Authorization: Bearer $T" "localhost:8080/api/v1/relational/codesets/$CS/sync?version_id=$V"
# 2b) или записать строку черновика «ячейка за ячейкой»
curl -X POST -H "Authorization: Bearer $T" -H 'Content-Type: application/json' \
  -d '{"branch_id":"001","branch_sgmnt_id":42,"label_ru":"Отделение 001"}' \
  "localhost:8080/api/v1/relational/codesets/$CS/draft-rows?version_id=$V"
# 3) опубликовать → пересборка __current из draft версии
curl -X POST -H "Authorization: Bearer $T" "localhost:8080/api/v1/relational/codesets/$CS/publish?version_id=$V"
# 4) прочитать published-снапшот
curl -H "Authorization: Bearer $T" localhost:8080/api/v1/relational/codesets/$CS/rows
# 5) убедиться, что это реальные таблицы
make psql   # \dt rd_data.*   и   SELECT * FROM rd_data."<base>__current";
```

## Дальше — оставшиеся стадии

- ~~**Stage 2-final — единственный источник**~~ ✅ **СДЕЛАНО** (см. раздел выше): dual-write
  на `__draft` из normal flow + publish-хук на пересборку `__current`. Остаётся как
  best-effort зеркало поверх ведущего `code_item`; «жёсткое» переключение source-of-truth —
  Stage 7.
- ~~**Stage 3 — read-path**~~ ✅ **СДЕЛАНО** (additive, см. раздел выше): проекция
  `rd_data` → `CodeItemDto`, эндпоинты `/items` и `/draft-items`. Hard-switch
  `CodeItemResource`/distribution на rd_data — Stage 7.
- ~~**Stage 4-full — bitemporal/hierarchy**~~ ✅ **СДЕЛАНО** (см. раздел выше): `system_*`,
  `parent_ref` (4a) + on-demand closure и cycle-detection по `parent_key` (4b). Материализация
  closure-таблицы и per-row `content_hash` вынесены в Stage 5 / при необходимости.
- ~~**Stage 5 — publish/diff/hash**~~ ✅ **СДЕЛАНО** (см. раздел выше): общий канонизатор
  `CanonicalSnapshot` + `content_hash` из `rd_data` (parity с `code_item`) + колоночный `diff`.
- ~~**Stage 6 — FK**~~ ✅ **СДЕЛАНО** (см. раздел выше): `column_refs` (E25) → реальные
  `FOREIGN KEY` между `__current` (defensive, только при unique-цели). Self-FK на
  `parent_key` отложен (нужны materialized parent-колонки).
- **Stage 7**: удалить JSONB-колонки/индексы из `code_item`/`code_set_schema` и снести
  generic-путь; переключить production read/write на `rd_data` (hard-switch).

## Stage 7 — hard-switch (полный снос jsonb), в работе

Цель: `rd_data` — единственный стор; убрать jsonb-колонки `code_item`/`code_set_schema`
и generic-путь. **Блокер**, найденный на старте: модель C хранила только *последний*
PUBLISHED-снапшот (`__current` перезатирается на publish'е), а distribution отдаёт
произвольный semver / `knowledge_as_of` (история всех версий). Поэтому простой drop
`code_item` сломал бы distribution. Решение (выбрано заказчиком) — сперва дать
реляционную историю версий.

### Stage 7a — история версий (`__history`) ✅ СДЕЛАНО

- Третья физическая таблица на справочник: `rd_data."<base>__history"` — форма как у
  `__draft` (`version_id` + data, PK `(version_id, ключи)`), но хранит снимок **каждой**
  опубликованной версии (не перезатирается). Суффикс `__history` (9 симв., как `__current`,
  укладывается в 63 при base≤54).
- `provision` создаёт три таблицы (draft/current/history) + ALTER для всех.
- `publish` в той же tx, что и пересборка `__current`, идемпотентно (по version_id)
  дописывает снимок версии в `__history`.
- Read: `RelationalStoreService.listPublishedItems(versionId)` → `CodeItemDto` из истории;
  REST `GET …/published-items?version_id=`. Это даёт distribution'у произвольную версию.
- Pure-тест на имя/длину `__history`.

### Дальше по Stage 7 (порядок)

- **7b — distribution читает items из `rd_data`** ✅ СДЕЛАНО: порт `RelationalReadPort`
  (api) + `RelationalReadAdapter` (authoring, поверх `listPublishedItems` из `__history`),
  wired в app через `AuthoringModule.buildRelationalReadPort`. `DistributionService`
  резолвит версию/кодсет как прежде (catalog + `code_set_version` — не jsonb), а items
  берёт из порта; фильтр `as_of` (effective) + пагинация + lookup — in-memory (пилотные
  объёмы). DAO-методы чтения items из `code_item` больше не вызываются (удалим в 7e).
  Pure-тест `DistributionServiceTest.effectiveAt`. NB: версии, опубликованные до Stage 7a,
  в `__history` отсутствуют — нужен re-publish/backfill (transition gap).
- **7c — write-flip** ✅ СДЕЛАНО: `code_item` больше не пишется/читается authoring'ом.
  Реляц.таблицы получили операционные колонки `id`(uuid)+`row_version`(integer) (в
  content_hash/diff не участвуют — `NON_CONTENT_COLUMNS`). `RelationalStoreService` —
  insert/update(CAS по row_version)/delete по id, `clearDraft`, `listDraftItemsPage`+
  `countDraftItems`, `findDraftItemById`/`ByKey`, `cloneDraftFromPublished` (из `__history`
  в `__draft` с новыми id; `codesetId` явно — новая версия может быть ещё не закоммичена).
  `AuthoringService` весь переключён (createDraft клонирует из истории; add/update/delete/
  bulk/clear/listItems/findItemByKey/diff; `item_count` из `__draft`).
- **7d — publishing canonical из `rd_data`** ✅ СДЕЛАНО: `PublishedSnapshotAdapter.
  canonicalSnapshotBytes` берёт строки версии из `__draft` (publishing читает их до
  publish-CAS) и хэширует тем же `CanonicalSnapshot` → `content_hash` сохраняется.
  `onVersionPublished` больше не зовёт `syncFromVersion` (draft уже источник).
- **7e — drop jsonb + снос generic-пути** ✅ КОД ГОТОВ (⚠️ **мерджить только после зелёного CI**):
  - Миграция `V025__drop_code_item_generic_path.sql`: `DROP TABLE code_item, code_item_closure`
    (CASCADE) + осиротевшие trigger-функции closure/cycle (V022/V023). `code_set_schema.json_schema`
    НЕ трогаем (вывод колонок rd_data + валидация attributes).
  - Java: удалены `ClosureAdminResource` (+ wiring, `rebuildClosure`), `CodeItemClosureDao`,
    `CodeItemDao`, `AuthoringMappers.toItem`; в `RelationalStoreService` убраны `syncFromVersion`/
    `toCells`/`ItemRow`/`SyncResult`/`mirror*` + `/sync` endpoint; в `AuthoringService` — мёртвый
    code_item-код; в `DistributionDao` — item-методы.
  - Оставлены мёртвыми (types-only, не вызываются): `CodeItemDiffDao` + `DiffCalculator`
    (нужны типы `Result/Entry/Summary` + их тест; ссылаются на ф-цию `code_item_diff_base`).
  - **Необратимо**: `V025` применится Flyway на старте. Локально вся сборка (8 модулей) зелёная,
    но rd_data-путь проверяют только Testcontainers ITs в CI — drop безопасен лишь после зелёного CI.

## Риски / открытые вопросы

- **Версионность**: выбран вариант C (draft + current). `__current` хранит только
  текущий PUBLISHED; полная история версий — в `__draft` по `version_id`. Если нужна
  bitemporal-история на стороне потребления — расширять отдельно (Stage 4).
- **Эволюция схемы**: добавление/удаление атрибута → `ALTER TABLE ADD/DROP COLUMN`
  обеих таблиц (миграция данных). Сейчас `provision` только `CREATE TABLE IF NOT EXISTS`.
- **Динамический DDL под прод-ролью**: `rdmmesh_app` получил `CREATE` на `rd_data` —
  оценить с точки зрения безопасности (отдельная роль/schema-allowlist).
- **Лимит имени**: базовое имя `<domain>__<codeset>` ≤ 54 (чтобы `__current`/`__draft`
  укладывались в 63); длинные имена → нужен хеш-суффикс.
- **publish без транзакционной координации с workflow**: relational `publish` сейчас
  отдельный вызов; в Stage 2-final его надо встроить в реальный publish-переход.
