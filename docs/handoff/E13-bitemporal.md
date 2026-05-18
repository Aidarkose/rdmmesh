# Handoff — Эпик E13 round 1 (Bitemporal UI + edit effective_*)

> **Аудитория документа.** AI-агенты и инженеры, подключающиеся к проекту после
> E13 round 1. Документ самодостаточен — переписки и контекста предыдущей сессии
> у вас нет, всё что нужно — здесь, в [`SPEC.md`](../../SPEC.md),
> [`E1-foundation.md`](E1-foundation.md), …, [`E11.2d-audit-viewer.md`](E11.2d-audit-viewer.md)
> и [`E12-ingestion.md`](E12-ingestion.md).
>
> **Дата handoff'а.** 2026-05-12.
> **Состояние.** E13 round 1 закрыт: UI-сторона bitemporal-эпика. Расширен
> inline-editor в `ItemsTable` (effective_from/to, parent_key, status,
> description_ru/en); расширен `AddItemModal` теми же полями; добавлен
> `ConsumerViewDrawer` для bitemporal-просмотра через distribution-endpoint.
> `npm run typecheck` ✅, `npm run build` ✅. **Backend остаётся без изменений**
> — все необходимые контракты (PATCH/POST authoring + GET /rdm distribution)
> уже работали с E4/E8.
> **End-to-end smoke не прогонялся** — `make up` (см. §6).
>
> **Round 1 осознанно НЕ включает:** closure-table rebuild через триггеры (это
> backend-направление, отложено на E13 round 2 — следующая сессия); tree-редактор
> для иерархических справочников Security/Access Matrix (отдельное UI-направление);
> cross-codeset reference editing (parent_ref, требует selector codeset'а — V1+);
> partitioning code_item по version_id (SPEC §3.4 — отдельная миграция).

---

## 0. TL;DR за 30 секунд

- **Что появилось в UI:**
  - **`ItemsTable` (inline editor)** теперь редактирует `effective_from`,
    `effective_to`, `parent_key`, `status` (ACTIVE/RETIRED), а в expand-row —
    `description_ru/en`. Сохранены: optimistic-lock через `expected_row_version`,
    одна-row-edit-в-момент, 409/422 обработка.
  - **`AddItemModal`** получил эти же поля + `Space.Compact` группировку
    `status+orderIndex` / `effective_from+to` / `description_ru+en`.
  - **`ConsumerViewDrawer`** — новая кнопка «Просмотр как consumer» на
    `VersionPage` для PUBLISHED/DEPRECATED версий. Drawer 80% с DatePicker
    `as_of`, DatePicker showTime `knowledge_as_of`, lang RU/EN, version input.
    Делает запрос к `GET /api/v1/rdm/{domain}/{codeset}/items` с этими
    параметрами. Показывает результат + content_hash + версию + total.
- **Backend не тронут.** PATCH `/versions/{id}/items/{itemId}` уже принимает
  `effective_from/to`, `parent_key`, `parent_ref`, `status`, `description_ru/en`
  с E4 (см. `CodeItemResource.ItemPatchRequest`). Distribution `/rdm/.../items`
  принимает `as_of`/`knowledge_as_of`/`lang`/`version` с E8.
- **Bundle:** app-код вырос с 24.5 → **27.5 KB gzip** (+ConsumerViewDrawer +
  расширенные ItemsTable/AddItemModal). vendor-antd без изменений — DatePicker
  уже был подтянут в E11.2d audit viewer.

---

## 1. Что сделано

### 1.1. Новые/обновлённые файлы

```
rdmmesh-ui/src/
├── api/
│   ├── endpoints.ts              ← +api.distributionItems, +DistributionQuery
│   ├── queryClient.ts            ← +qk.distribution.items
│   └── types.ts                  ← +DistributionItem, +DistributionItemsPage
├── components/
│   ├── ItemsTable.tsx            ← +inline-edit effective_*/parent_key/status
│   │                                +expand-row description_ru/en + dayjs
│   ├── AddItemModal.tsx          ← +те же поля; layout перегруппирован Space.Compact
│   └── ConsumerViewDrawer.tsx    ← новый: bitemporal viewer
├── pages/VersionPage.tsx         ← +load codeset + domain (для name'ов);
│                                    +ConsumerViewButton для PUBLISHED/DEPRECATED
└── i18n/{ru,en}.json             ← +items.parentKey/descriptionRu/En/effectiveFrom/To/
                                      statusValues +consumer.* (полная секция)
```

### 1.2. Inline editor — расширенный UX

Теперь edit-режим открывает не только labels/attributes/orderIndex, но и:

| Поле | Контрол | Парсинг при save |
|---|---|---|
| `parent_key` | `Input` строкой | `"DEPT"` → `["DEPT"]`; `["A","B"]` → JSON.parse; пусто → null (root) |
| `status` | `Select<ACTIVE \| RETIRED>` | as-is |
| `effective_from` | `DatePicker` (dayjs) | `format("YYYY-MM-DD")` → ISO date string |
| `effective_to` | `DatePicker` (dayjs) | то же |
| `description_ru/en` | `Input.TextArea` (в expand-row) | trim + null если пусто |

**Expand-row behaviour:**
- Read-mode → expand показывает `description_ru/en` как текст (или плейсхолдер).
- Edit-mode → expand принудительно открывается для редактируемой row, внутри
  textarea'ы — это компактнее чем тащить description-колонки в основную таблицу.
- Управление через `expandable.expandedRowKeys = editingId ? [editingId] : undefined`.

**Optimistic-lock не тронут** — `expected_row_version` по-прежнему передаётся в
PATCH body. 409 → invalidate + откат UI (как в E11.2b).

### 1.3. AddItemModal — те же поля

Layout:

```
[ Key (key_parts)                    ]
[ Parent (parent_key, опц.)          ]
[ Label RU              | Label EN   ]
[ Description RU        | Desc. EN   ]
[ Attributes (JSON-textarea)         ]
[ Status (ACTIVE)       | Order      ]
[ Effective from        | Eff. to    ]
[ Alert (parse error)                ]
```

`initialValues={{status:"ACTIVE"}}` — дефолт совпадает с backend'ом (SPEC §3.4).
Парсинг ошибок (`SyntaxError` на parent_key/attributes) ловится синхронно в
`mutationFn` и попадает в `<Alert>` под формой; модал не закрывается.

### 1.4. ConsumerViewDrawer — bitemporal viewer

**Кнопка** «Просмотр как consumer» появляется в Space actions на VersionPage
только для PUBLISHED/DEPRECATED версий (DRAFT/IN_REVIEW не попадают в distribution
по design — handoff E8 §1.2).

**Drawer 80% width** содержит:

```
[ Card: Фильтры ]
[ Версия | as_of (date) | knowledge_as_of (date+time) | lang | Применить | Сбросить ]
[ если активны bitemporal-фильтры → <Alert type="info"> ]

[ Descriptions (compact): version resolved | status | total | published_at | content_hash ]
[ Table: keyParts | label | parentKey | <attr.*> | status | effective_from | effective_to | order ]
```

**`distribution` endpoint** возвращает уже-выбранный `label` (по lang) — без
раздельных `label_ru`/`label_en`. Это backend-side fallback (handoff E8 §1.4):
`lang=ru` → `label_ru ?? label_en`; `lang=en` → наоборот.

**Resolve domain.name + codeset.name.** Distribution работает по qualified_name,
не UUID. VersionPage теперь дополнительно подгружает:
- `getCodeSet(codesetId)` → `codeset.name`,
- `getDomain(codeset.domain_id)` → `domain.name`.

Эти запросы используют общие qk.codesets.one / qk.domains.one — кэш переживает
между навигациями (staleTime 30s).

**Контракт ошибок:**
- 404 от backend (например, на запрошенный `as_of` ни одной known версии нет) →
  inline `<Alert type="error">` под формой; таблица не показывается.
- 400 (например, `version=DROP TABLE`) → тоже Alert.

### 1.5. Что backend уже поддерживает (без правок)

Доказательство для следующего агента, чтобы не лазить в Java:

**`CodeItemResource.java:266`** (E4) — `ItemPatchRequest`:
```java
@JsonProperty("parent_key")     public List<String> parentKey;
@JsonProperty("parent_ref")     public Map<String, Object> parentRef;
@JsonProperty("description_ru") public String descriptionRu;
@JsonProperty("description_en") public String descriptionEn;
@JsonProperty("status")         public String status;
@JsonProperty("effective_from") public String effectiveFrom;
@JsonProperty("effective_to")   public String effectiveTo;
```

**`RdmDistributionResource`** (E8) — GET `/rdm/{domain}/{codeset}/items` с
параметрами `version`, `as_of` (YYYY-MM-DD), `knowledge_as_of` (ISO instant),
`lang`, `page`, `size`. Подробности — handoff E8 §1.2 / §1.4 / §2.

### 1.6. Зависимости

`dayjs` уже был в `node_modules` через AntD 5 peer-deps; явно в `package.json`
не добавлен. AuditPage (E11.2d) уже его использует. Если AntD сделает breaking
change на dayjs — обновится у нас транзитивно. На пилоте этого достаточно.

`@tanstack/react-query` 5.100.9 — без изменений.

---

## 2. Контракт: что UI ожидает от backend'а

### 2.1. Новое в round 1

| Endpoint | Метод | Используется в | Auth |
|---|---|---|---|
| `/api/v1/rdm/{domain}/{codeset}/items` | GET | ConsumerViewDrawer | любая authenticated (E8 §1.1) |

Query-параметры: `version`, `as_of`, `knowledge_as_of`, `lang`, `page`, `size`.
Семантика — см. handoff E8 §1.2.

**Response shape — camelCase**, не путать с authoring/ItemsPage (snake_case):

```ts
interface DistributionItemsPage {
  domain: string;
  codeset: string;
  version: string;     // resolved semver
  versionId: string;
  status: "PUBLISHED" | "DEPRECATED";
  contentHash?: string | null;
  publishedAt?: string | null;
  page: number; size: number; total: number;
  items: DistributionItem[];
}
```

### 2.2. Без изменений

PATCH / POST authoring `/versions/{id}/items/*` — те же, но теперь UI шлёт
больше полей (effective_*, parent_key, status, description_*). Backend
принимает их с E4. Семантика ошибок — без изменений (409/422/400).

---

## 3. Что осталось доделать в E13 — backend-направление (round 2)

Round 1 — UI-only quick win. Backend-сторона E13 остаётся открытой и **должна
быть закрыта раундом 2** до того как пилот Security/Access Matrix начнёт
загружать реальные данные (десятки тысяч позиций × систем).

### 3.1. Closure-table rebuild через триггеры или batch'и

**Текущее поведение** (handoff E4 §3 #1):
- INSERT/UPDATE/DELETE CodeItem → `WITH RECURSIVE` через всё дерево.
- На draft'ах <100 items — мгновенно.
- На draft'ах >1000 items — начинает тормозить (квадратичный rebuild).
- Для Security/Access Matrix (Position×System ~10⁴–10⁵ rows) — неприемлемо.

**Варианты решения:**
1. **BEFORE/AFTER-триггеры на `authoring.code_item`** — incremental update
   `code_item_closure` по delta. Pro: атомарно с write'ом, не нужны batch'и.
   Con: триггерная логика хрупкая на UPDATE parent_key (move узла = удалить
   старые ancestors + добавить новые).
2. **Batch-rebuild по флагу `dirty`** — INSERT помечает version'ю
   `closure_dirty=true`, рекурсивный rebuild делается отдельным background-job'ом
   или при publish'е. Pro: батч-производительно. Con: API `/items/{id}` может
   возвращать stale closure до next batch.
3. **Recursive CTE в DAO, без таблицы closure** — для пилотных объёмов <100K
   subtree-запрос через CTE имеет приемлемую latency. Закрывает функциональность
   без отдельной таблицы. Pro: один источник истины. Con: запросы по closure
   медленнее, чем по индексированной таблице, при больших объёмах.

**Рекомендация:** начать с (1) — триггеры на INSERT/DELETE/UPDATE-parent_key.
Покрыть тестами move-сценарии. Миграция V022:
```sql
CREATE OR REPLACE FUNCTION authoring.code_item_closure_upsert(...)
  RETURNS trigger LANGUAGE plpgsql AS $$ ... $$;
CREATE TRIGGER code_item_closure_trg
  AFTER INSERT OR UPDATE OF parent_key OR DELETE ON authoring.code_item
  FOR EACH ROW EXECUTE FUNCTION authoring.code_item_closure_upsert();
```

### 3.2. Partitioning `code_item` по `version_id`

SPEC §3.4 явно говорит: «партиционирование `code_item` по `version_id`
**отключено в MVP** (объёмы небольшие), но pluggable — добавляется DDL-миграцией
без рефакторинга кода.» После closure-trigger'ов имеет смысл и
LIST-партиционирование (по `version_id`-buckets) — DEPRECATED-версии,
переставшие активно читаться, можно вытеснять в cold-storage партиции.

Это V14 territory (compliance hardening — handoff E14).

### 3.3. Tree-редактор для иерархических справочников

Round 1 расширил inline-edit `parent_key` через Input — это работает, но UX
для иерархии слабый (нельзя видеть дерево). Round 2 UI:

- **Antd `<Tree>`** + drag-and-drop узлов → `apiMutations.patchItem` с новым
  `parent_key`.
- Переключатель «Plain grid / Tree view» на VersionPage для CodeSet'ов с
  `hierarchy_mode=INTRA_CODESET`.
- Для `hierarchy_mode=CROSS_CODESET` (cross-codeset references через
  `parent_ref`) — selector кросс-codeset'а + key. На пилоте Risk/IFRS9 не
  требуется; для Security/Access Matrix — нужно.

Зависимости с round 1: optimistic-lock на move'ах (parent_key change тоже
инкрементирует row_version). Closure-table должен быть консистентен после
move — это связывает round 2 с §3.1.

### 3.4. UI-improvements мягкого тира

1. **Composite-key DatePicker fallback.** Если `key_spec.parts[i].type === "DATE"`,
   key-input должен быть DatePicker, не Input. Сейчас все key parts — Input.
   Не блокирует пилот (IFRS9 stages — STRING).
2. **`parent_ref` UI** — cross-codeset reference. Нужен codeset-selector через
   `api.listDomains()` → `listCodeSetsByDomain` cascade. V1+.
3. **Auto-resolve `parent_key` из uppercased current row** — для UX'а, чтобы
   автор не помнил semver/иерархию вручную. V1+.

---

## 4. Технический долг и решения

| Что | Где | Когда снять / следующий шаг |
|---|---|---|
| Closure-table rebuild по `WITH RECURSIVE` на каждый write | `CodeItemClosureDao` | E13 round 2 — триггеры (см. §3.1) |
| `parent_key` редактируется как строка / JSON-array | `ItemsTable`, `AddItemModal` | E13 round 2 — tree-editor (§3.3) |
| `parent_ref` (cross-codeset) не редактируется в UI | `ItemsTable`, `AddItemModal` | V1+, требует cascade codeset-selector'а |
| `description_ru/en` показываются в expand-row, не в основной таблице | `ItemsTable` | by design — иначе таблица расползается |
| `dayjs` не явный в `package.json` (transitively через AntD) | `package.json` | оставить — AntD 5 гарантирует |
| ConsumerView запрашивает size=1000 фикс., без курсорной пагинации | `ConsumerViewDrawer` | Для пилотных codeset'ов (десятки items) ОК. При росте — добавить server-side pagination через AntD Table `onChange` |
| Distribution API использует name (snake_case), authoring — UUID | разные endpoints | by design — distribution-side stable URL'ы для consumer-систем |
| `qk.distribution.items(...)` ключи включают объект фильтров напрямую | `queryClient.ts` | React Query умеет structural compare; стабильно |
| Кнопка ConsumerView появляется только для PUBLISHED/DEPRECATED | `VersionPage` | by design — DRAFT'ы недоступны через distribution |

---

## 5. Что планируется в Round 2 (E13.2 / backend)

| Задача | Сложность | Зависимости |
|---|---|---|
| Триггеры `code_item_closure_*` + миграция V022 | M | — |
| Перевод DAO closure rebuild на push-семантику + тесты | M | триггеры |
| UI tree-editor `<Tree>` с drag-and-drop | M | closure-стабилен |
| Benchmark: closure rebuild на 10k items | S | триггеры |
| (опц.) LIST partitioning V023 для `code_item` по `version_id` | L | V14 |

Round 2 backend закрывает критический tech-debt из handoff E4 §3 #1 и
открывает дорогу пилоту Security/Access Matrix.

---

## 6. Smoke

### 6.1. Локальный build (то, что прошло на 2026-05-12)

```bash
cd rdmmesh-ui
npm run typecheck            # exit 0
npm run build                # exit 0:
                             #   index             91.6 KB / 27.5 KB gzip  (+3 KB от E13)
                             #   vendor-react     162.8 KB / 53.2 KB gzip
                             #   vendor-query     171.4 KB / 48.4 KB gzip
                             #   vendor-antd    1 181.6 KB / 368.6 KB gzip (без изменений)
```

### 6.2. End-to-end (требует `make up`; **не прогонялся в этой сессии**)

```bash
# 1. backend
make up

# 2. UI
make ui-install   # один раз
make ui

# 3. в браузере как dev-author:
#
#   ИНЛАЙНОВЫЙ EDIT расширен (на DRAFT-версии):
#   - Edit на любую row → видны новые поля:
#       parent_key (Input, плейсхолдер DEPT / ["DEPT","DIV"])
#       status (Select ACTIVE/RETIRED)
#       effective_from (DatePicker)
#       effective_to (DatePicker)
#       expand-row автоматически открывается → description_ru/en (TextArea)
#   - Save → backend PATCH с новым набором полей →
#     SELECT FROM authoring.code_item WHERE id='<id>'
#     должен показать обновлённые effective_from/to/parent_key/status/desc_*
#
#   ADD ITEM расширен:
#   - Кнопка «Добавить запись» → modal с полным набором полей
#   - status default = ACTIVE
#   - effective_from = today (опционально)
#   - description_ru/en в одной строке как Space.Compact
#
#   CONSUMER VIEW (на PUBLISHED-версии):
#   - Кнопка «Просмотр как consumer» (Eye icon) появляется рядом с Diff
#   - Drawer 80% → Card "Фильтры":
#       Версия (default — current resolved version)
#       as_of (DatePicker)
#       knowledge_as_of (DatePicker showTime)
#       Язык (ru / en)
#       [Применить] [Сбросить]
#   - Применить → запрос к GET /api/v1/rdm/{domain}/{codeset}/items
#   - Card Descriptions: version resolved / status / total / published_at / content_hash
#   - Table items по distribution-стороне с camelCase shape
#
#   BITEMPORAL SCENARIOS:
#   - as_of = 2025-12-31 (до effective_from'ов) → total=0
#   - as_of = today → текущие active items
#   - knowledge_as_of = 2026-01-01 (до publish'а) → backend 404 → Alert
#   - knowledge_as_of = now → как без него
#   - version = 0.1.0 явно → конкретная (даже DEPRECATED)
#   - lang = en → labels по-английски
```

Ожидаемые ошибки:
- 404 на `as_of` без known версии → inline Alert; таблица не показывается.
- 400 на bad UUID / version → message.error от ApiError.
- 422 на PATCH (например, attributes invalid) → message.error (как в E11.2b).
- Парсинг parent_key (битый JSON) → message.error.

---

## 7. Открытые вопросы (актуальны для команды банка)

Без изменений с E12, плюс E13-specific:

1–22 — без изменений (см. `E12-ingestion.md` §7).
23. **Closure-table strategy.** Триггеры (атомарно) vs batch (производительно)
    vs recursive CTE (без отдельной таблицы)? Решить до E13 round 2 — это
    влияет на тесты и миграционный путь.
24. **Tree-editor скоуп**: только INTRA_CODESET в round 2, или сразу с
    CROSS_CODESET (parent_ref + cascade codeset-selector)? CROSS_CODESET
    в пилоте Risk/IFRS9 не нужен; Security/Access Matrix нуждается (City →
    Country reference).
25. **`effective_from`/`effective_to` UX в bulk-import.** Сейчас CSV/JSON
    bulk принимают эти поля (см. handoff E4 §1.8), но preview frontend'ом не
    показывается. С E13 — стоит проверить в smoke, что они правильно
    парсятся и сохраняются через bulk.
26. **`description_ru/en` в bulk-import.** То же что 25 — backend принимает,
    но frontend hint про колонки CSV в E11.2b §1.3 их не упоминает. Добавить
    в `bulk.csvHint` ключ i18n. **Сделать в E13 round 2** как мягкий cleanup.

---

## 8. Указатели на следующие эпики

> Конкретное содержание — в SPEC §5.1.

### E13 round 2 (backend + tree-editor)

См. §3 и §5.

### E14. Compliance hardening

- Криптографическая audit-цепочка (`prev_hash`/`entry_hash` уже в V070; алгоритм
  + verify endpoint).
- Audit-export в S3 immutable bucket.
- Унифицированное atomic-decision для split-tx случаев E5/E6/E7/E9/E10.
- LIST partitioning `code_item` по `version_id` (handoff E1 §3.7 / SPEC §3.4).
- Security review (OWASP Top 10).

---

## 9. Версия документа

- **0.1** — 2026-05-12. Создан после реализации E13 round 1 (UI-сторона
  bitemporal: inline editor effective_*/parent_key/status/description_*,
  AddItemModal расширен, ConsumerViewDrawer для distribution-стороны).
  `npm run typecheck` и `npm run build` зелёные. End-to-end smoke — следующая
  сессия. Backend без изменений. Автор: Claude Opus 4.7.
