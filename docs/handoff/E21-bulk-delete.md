# E21 — Bulk delete всех items в DRAFT (Slice A)

## TL;DR

Очистка DRAFT-версии одной операцией вместо построчного `DELETE`. Полезно
до повторного bulk-импорта (CSV/XLSX), когда Author хочет начать заполнение
заново, и при ошибочном клонировании из устаревшей published-версии.

Действие — только на DRAFT, только для `RDM_AUTHOR`/`RDM_ADMIN`, защищено
обязательным `?confirm=clear-all` query-параметром и двухступенчатым
UI-диалогом (ввод фразы подтверждения). Closure-table вычищается через
AFTER-DELETE триггер (V022), `item_count` сбрасывается в 0.

**Status:** Slice A — clear all in current DRAFT. Селективный bulk-select
и фильтры — отложены (см. §«Дальше»).

## Что в этом слайсе

| Часть | Где | Что делает |
|------|-----|-----------|
| DAO | `rdmmesh-authoring/.../dao/CodeItemDao.java` | `deleteAllInDraft(versionId)` с EXISTS-проверкой DRAFT |
| Service | `rdmmesh-authoring/.../service/AuthoringService.java` | `clearAllItems(versionId, actor)` → транзакция, log, `item_count=0` |
| REST | `rdmmesh-authoring/.../resource/CodeItemResource.java` | `DELETE /versions/{versionId}/items?confirm=clear-all` |
| API client | `rdmmesh-ui/src/api/endpoints.ts` | `apiMutations.clearAllItems(versionId)` |
| UI | `rdmmesh-ui/src/components/ClearAllItemsButton.tsx` | Кнопка + Modal с вводом фразы `clear-all` |
| Wiring | `rdmmesh-ui/src/pages/VersionPage.tsx` | Кнопка в `extra` карточки «Items» (только DRAFT и при `item_count > 0`) |
| i18n | `rdmmesh-ui/src/i18n/{ru,en}.json` | `items.clearAll.*` ключи |

## Что НЕ в этом слайсе

- **Selective bulk-delete** (multi-select checkbox в grid → удалить пачку).
  Это другая модель UX и другой endpoint (`DELETE` с `?ids=...` body).
- **Удаление по фильтру** (например, всё RETIRED или `attributes.segment='X'`).
  Слишком опасно без отдельного UI-конструктора фильтра — Slice B.
- **Cascading delete** парных артефактов draft'а: workflow-tasks, audit-row'ы
  не трогаются, потому что они логически отдельные (draft версия остаётся,
  меняется только её содержимое — items уходят, но `code_set_version` row
  жива). Это **не** `DeleteDraftButton` — тот удаляет всю версию целиком.
- **Soft-delete / undo**. Действие необратимо. Если нужен undo — Author
  делает diff с published-версией и реимпортирует. Подробнее в §«Открытые
  вопросы».

## Контракт REST

```
DELETE /api/v1/versions/{versionId}/items?confirm=clear-all
Authorization: Bearer <jwt>          // RDM_AUTHOR | RDM_ADMIN
```

| Код | Когда | Тело |
|-----|-------|------|
| 200 | OK | `{ "deleted": <int> }` |
| 400 | `confirm` ≠ `"clear-all"` | `{ message: "confirm=clear-all is required" }` |
| 404 | Версия не найдена | стандартный |
| 409 | Версия не в DRAFT | `{ message: "Version <id> is <status>, only DRAFT is editable" }` |

Идемпотентность: повторный вызов на пустой DRAFT возвращает `200 { deleted: 0 }`.

## Контракт UI

1. Кнопка `Очистить все записи` отображается в карточке «Items» рядом с
   `Add` и `Bulk import` — **только** если:
   - `version.status === "DRAFT"`,
   - `version.item_count > 0`,
   - пользователь имеет `RDM_AUTHOR` или `RDM_ADMIN` (защита снимется
     на 403 от backend'а в любом случае).
2. Click → AntD `Modal.confirm` с input'ом. Ok-кнопка `disabled`, пока
   пользователь не введёт ровно `clear-all` (case-sensitive).
3. После успеха: toast `t("items.clearAll.success", { n })`, invalidate
   `qk.versions.items*`, `qk.versions.one(vid)` (item_count обновится).

## Инварианты и взаимодействия

- **DRAFT-only**: проверяется и в service (`loadDraftContext`), и в DAO
  (EXISTS-clause). Не полагаемся только на одну точку — defense in depth.
- **Audit**: одна строка-запись через стандартный logger (`log.info`).
  Append-only audit-таблица (E10) подцепит event через event-bus, если
  он сконфигурирован — отдельной hook'и не добавляем.
- **Closure-table**: триггер `code_item_closure_delete_trg` (V022) на
  каждый удалённый row делает `DELETE FROM closure WHERE ancestor OR
  descendant = OLD`. На MVP-объёмах (≤ 10⁵ items) — приемлемо. Если
  становится bottleneck — отдельный `TRUNCATE closure WHERE version_id`
  до `DELETE code_item` (Slice C).
- **`item_count` в code_set_version**: сбрасываем в 0 в той же
  транзакции, чтобы UI-баннер «у вас 0 items» появился сразу.
- **Optimistic lock**: не применяется. `row_version` — на per-item edit
  внутри живой версии; здесь мы её зачищаем целиком, конкурентный
  редактор в любом случае получит 0 affected rows при следующем PATCH.

## Smoke plan

1. Создать DRAFT из последней published в `dpd_buckets` (5 items). UI
   показывает кнопку «Очистить все записи» рядом с `Bulk import`.
2. Нажать → ввести `wrong` → кнопка OK остаётся disabled.
3. Ввести `clear-all` → OK → toast `Удалено 5 записей`, grid пустой,
   `item_count = 0`, History-таб пуст (это не workflow-переход).
4. Повторный клик → видим, что кнопки больше нет (item_count = 0).
5. Создать version transition → status `IN_REVIEW`. Кнопка исчезает
   (не DRAFT).
6. Backend: вызов без `?confirm` → 400, вызов на PUBLISHED → 409, вызов
   на несуществующей версии → 404.

## Дальше (Slice B/C)

- **B1 — selective bulk-delete**: checkbox-колонка в `ItemsTable`,
  toolbar-кнопка «Удалить выбранное (N)», endpoint
  `DELETE /versions/{id}/items` с body `{ "ids": [...] }`. Контракт
  ortho к Slice A (другой URL-shape).
- **B2 — фильтр-удаление**: для bulk-операций Security/Access Matrix
  (BR-16) нужно «удалить все права для уволенной должности» —
  endpoint `DELETE /versions/{id}/items?filter=<jsonpath>` после того,
  как backend научится резолвить filter-DSL.
- **C1 — оптимизация closure**: вместо row-by-row триггера —
  `TRUNCATE closure WHERE version_id` одним statement'ом до DELETE.
- **C2 — undo буфер**: при clear-all делать snapshot items в
  shadow-table `authoring.code_item_clear_buffer` с TTL 24 часа, чтобы
  Author мог откатить за 1 клик. Полезно для тех, кто кликает не глядя.

## Открытые вопросы

- **Нужно ли отдельное право `RDM_BULK_DELETER`?** Сейчас полагаемся
  на `RDM_AUTHOR` — это та же роль, что для add/patch/delete-by-id.
  Если comp-команда захочет «удалять пачкой могут только Steward'ы» —
  добавим в Slice B.
- **Аудит-payload**: пока пишем только `version_id` и `n_deleted`.
  Не сохраняем snapshot удалённых items. Если регулятор IFRS9 спросит
  «что именно было удалено в этом DRAFT» — ответ «ничего: версия не
  была publish'ом, а DRAFT'ы по 7-летнему retention не покрыты».
  Если позиция изменится — Slice C2 закроет это автоматически.
- **Конкурентный bulk import + clear-all**: оба идут в DRAFT, оба
  атомарны. Сценарий «один Author кликает clear-all, другой в этот
  момент жмёт import» — last-write-wins, потенциально странный результат
  (импорт может попасть в окно между DELETE и `setItemCount`). Постгрес
  сериализует, но порядок неопределён. На практике — один Author на
  draft, тоже что и в E4 (optimistic lock не покрывает version-level
  ops). Решение: оставить как есть, документировать.
