# Handoff — Эпик E19 (Credit Risk Matrices: rating transition + delinquency buckets + PD/LGD/EAD)

> **Аудитория документа.** AI-агенты и инженеры, которые будут **реализовывать**
> E19. Документ самодостаточен — переписки и контекста сессии у вас нет, всё что
> нужно — здесь, в [`SPEC.md`](../../SPEC.md) (§1.3 «Risk/IFRS9», §2.3
> bitemporal, §2.5 BR-03/BR-05/BR-09, §3.4 доменная модель, §3.5 REST),
> [`E3-catalog.md`](E3-catalog.md), [`E4-authoring.md`](E4-authoring.md) §1.8
> (CSV-формат bulk-import), [`E11.2b-ui-editor.md`](E11.2b-ui-editor.md),
> [`E13.2-hierarchy.md`](E13.2-hierarchy.md) (cross-codeset refs),
> [`E15-excel-io.md`](E15-excel-io.md) (XLSX import/export, на нём строимся),
> [`E18-admin-domain-management.md`](E18-admin-domain-management.md)
> (bootstrap-домен для `credit_risk`).
>
> **Дата.** 2026-05-26.
> **Состояние.** **Slice C — Commits 1, 2, 3 выполнены.** Backend: 49/49
> unit-тестов зелёные (включая 9 новых `MatrixPivotSheetParserTest` +
> 1 `MatrixPivotFixtureCompatTest`). ArchUnit: 11/11. Frontend:
> `npm run typecheck` + `npm run build` зелёные. **Что работает end-to-end:**
> `make up && make seed-credit-risk && make ui` → домен `credit_risk_<sfx>`
> с тремя CodeSet'ами; на rating_transition_matrix виден toggle
> `Таблица / Матрица` → pivot-вью 5×5 с переключателем горизонта; в
> BulkImportModal появляется таб **«Pivot Matrix»** с upload XLSX,
> переключателем horizon и radio `IMPLICIT_DEFAULT / STRICT`. Fixture
> `bootstrap/seed/credit_risk/transition-1Y.xlsx` (1.7 KB, 4×4 по матрице P
> заказчика) генерируется `scripts/gen-transition-fixture.py` (stdlib-only),
> совместима с fastexcel-reader. **Validators (`row_stochastic`,
> `absorbing_state_consistency`, bucket-валидаторы) — НЕ входят в Slice C**,
> это E19 V1 (см. §8 out-of-scope handoff'а).
>
> **Прагматический выбор Slice C** (§2.3): вместо отдельной колонки
> `catalog.code_set.kind` (миграция + JSON Schema + codegen + DAO/DTO/TS
> plumbing) используется существующее поле `tags` с конвенцией
> `kind:transition_matrix` / `kind:rating_scale` / `kind:delinquency_buckets`.
> Это убирает ~9 правок плумбинга backend↔frontend ценой чуть менее «чистого»
> дискриминатора. Для прод-V1+ — переход на отдельную колонку, если понадобится
> queryable filter; пока tags-based достаточно (UI делает
> `codeset.tags?.includes('kind:transition_matrix')`).
> Зависимости: E3 (CodeSet/CodeSetSchema) ✓, E4 (authoring, draft, bulk-import) ✓,
> E5 (workflow 4-eyes) ✓, E11.2b (UI grid-редактор) ✓, E13.2 (cross-codeset
> refs, `HierarchyMode.CROSS_CODESET`) ✓, E15 (XLSX import/export) ✓,
> E18 (admin bootstrap-домена) ✓.

---

## 0. TL;DR за 30 секунд

- Новый бизнес-домен **`credit_risk`** (создаётся через bootstrap-REST E18,
  потом mirror'ится из OpenMetadata) и **семейство CodeSet'ов** под кредитный
  риск-департамент: rating-scale, rating transition matrix, delinquency buckets,
  PD/LGD/EAD.
- Никакого нового движка таблиц **не строим** — каждая матрица это обычный
  CodeSet с composite key, который автоматически рендерится в существующем
  TanStack-grid (E11.2b). Поверх — **pivot-вью** для матриц, тонкий UI-компонент.
- Новый XLSX-парсер **`MatrixPivotSheetParser`** в `rdmmesh-authoring`:
  принимает «квадратную» матрицу (заголовки строк = `from`, заголовки колонок
  = `to`), раскладывает в `(from, to, horizon, probability)`-triples. Делегирует
  в существующий `CsvBulkParser.buildRow` (DRY-приём E15 §1.2). Long-формат
  (готовые triples) тоже поддерживаем — через стандартный XLSX-импорт E15.
- Поведение на не-стохастических строках — **`IMPLICIT_DEFAULT` по умолчанию**
  (дописываем колонку `D` из невязки 1−Σ) + явный switch `STRICT` в импортёре.
  Выбор зафиксирован заказчиком (см. §1 п.4).
- Новый custom domain-validator **`row_stochastic`** в authoring: сумма
  вероятностей по `(from, horizon)` = 1 ± 1e-3. Ходит за `WorkflowPort` тем же
  путём, что валидация по JSON Schema.
- Pivot-вью **`RatingTransitionPivotView`** в `rdmmesh-ui/`: рендерит матрицу
  `from × to`, переключатель горизонта (`horizon` — часть ключа), toggle
  Pivot ⇄ Long, bulk-paste из Excel. Подсветка absorbing-state (D→D=1).
- **Не делаем в E19:** автоматический расчёт PD из вероятностей миграций
  (Markov-аналитика — это потребитель данных, не RDM); прогнозирование
  макросценариев; GUI для просмотра evolution матрицы по `as_of`.

Маппинг 4 решений заказчика → разделы: (1) IMPLICIT_DEFAULT по умолчанию + switch
→ §3.3 + §4.2; (2) домен `credit_risk` → §2.1 + §7; (3) полный набор
горизонтов `{1M, 3M, 6M, 1Y, 3Y, 5Y}` → §2.2 enum; (4) «таблица сама» — §6 UI.

---

## 1. Постановка (из требований заказчика)

1. Импорт **матрицы вероятности миграции рейтингов** (rating transition matrix,
   `from × to → probability`) — пример заказчика 4×4 (без явной колонки Default,
   невязка 1−Σ = вероятность дефолта). Размерности фиксированы шкалой рейтингов.
2. Импорт **матрицы корзин просрочки** (delinquency buckets):
   `0–30 дней`, `30–90 дней`, `90+ дней = Default`. Используется как
   classification rule для PD и для трансляции в IFRS9 SICR-стадии.
3. **Кредитный департамент** — отдельный бизнес-домен в OpenMetadata
   (`credit_risk`), не подэпик Risk/IFRS9 (хотя терминологически близок).
4. Система должна **сама из импорта построить таблицу** — pivot-вью с
   возможностью inline-edit, без отдельного админ-tooling для каждого типа
   матрицы.
5. Горизонты transition-матрицы — **весь набор** `{1M, 3M, 6M, 1Y, 3Y, 5Y}`,
   пользователь выбирает в UI и в импорте.
6. Поведение на не-стохастических строках — **`IMPLICIT_DEFAULT` по
   умолчанию** + явный switch (заказчик подтвердил, что публикует матрицы
   именно в формате без явной колонки D).

---

## 2. Доменная модель и миграции

### 2.1. Новый домен `credit_risk`

Создаётся через bootstrap-REST E18 (`POST /api/v1/admin/domains` с
`om_domain_id`, `name=credit_risk`, `display_name`), потом приходит как mirror
из OpenMetadata по webhook'у E7. **Не** создаётся миграцией — это runtime-данные
(SPEC §2.4: домены — мастер OM, в миграциях не зашиваются).

В seed для dev/smoke добавить в `bootstrap/seed/domain-role-directory.example.json`
(или новый seed-файл) Steward'а и Business Owner'а домена `credit_risk` —
иначе E17 submit не пройдёт.

### 2.2. Новые CodeSet'ы (четыре)

CodeSet — runtime-сущность (создаётся через UI, не миграцией). Но **схемы**
(JSON Schema) и **seed данных** для пилота кладём в репо как
`rdmmesh-spec/schema/codesets/credit_risk/*.json` и
`bootstrap/seed/credit_risk/*.json`.

#### 2.2.1. `rating_scale` (внутренняя шкала рейтингов банка)

- **Key:** single, `code` (text).
- **Hierarchy:** `NONE`.
- **Schema** (`rating_scale.schema.json`):
  ```json
  {
    "type": "object",
    "required": ["order", "is_absorbing"],
    "properties": {
      "order":        {"type": "integer", "minimum": 1},
      "is_absorbing": {"type": "boolean"},
      "description":  {"type": "string"}
    },
    "additionalProperties": false
  }
  ```
- **Seed (пример заказчика 4 грейда + D):**
  ```
  AAA  order=1  is_absorbing=false  label_ru="Высший"        label_en="Highest"
  A    order=2  is_absorbing=false  label_ru="Высокий"       label_en="High"
  BB   order=3  is_absorbing=false  label_ru="Средний"       label_en="Medium"
  B    order=4  is_absorbing=false  label_ru="Низкий"        label_en="Low"
  D    order=5  is_absorbing=true   label_ru="Дефолт"        label_en="Default"
  ```

#### 2.2.2. `rating_transition_matrix`

- **Key:** composite `(from_rating, to_rating, horizon)`.
- **Cross-codeset refs:** `from_rating` и `to_rating` → `rating_scale.code`
  (`HierarchyMode.CROSS_CODESET`, E13.2). `horizon` — enum, **не** ref.
- **Schema:**
  ```json
  {
    "type": "object",
    "required": ["probability"],
    "properties": {
      "probability": {"type": "number", "minimum": 0, "maximum": 1},
      "source":      {"type": "string", "description": "Где взято — Moody's / S&P / внутренняя оценка"},
      "as_of_obs":   {"type": "string", "format": "date", "description": "Дата наблюдения, по которой оценена матрица"}
    },
    "additionalProperties": false
  }
  ```
- **`horizon` enum:** `["1M", "3M", "6M", "1Y", "3Y", "5Y"]` (фиксированный
  список, согласован с заказчиком; расширение — через CodeSetSchema-version-bump).
- **Domain validators:**
  - `row_stochastic`: для каждой пары `(from_rating, horizon)` сумма
    `probability` по всем `to_rating` = 1 ± 1e-3. Реализация — §4.2.
  - `absorbing_state_consistency`: для каждой пары `(from_rating=D, horizon)`:
    `(D, D, horizon).probability == 1.0` и `(D, X≠D, horizon).probability == 0`.
- **`kind`-маркер:** в `code_set.kind = 'transition_matrix'` (новая колонка
  `kind text` в `catalog.code_set`, §2.3) — UI читает её, чтобы выбрать
  pivot-вью вместо стандартного grid.

#### 2.2.3. `delinquency_buckets`

- **Key:** single, `bucket_code` (text). Примеры значений: `BUCKET_0_30`,
  `BUCKET_30_90`, `BUCKET_90_PLUS`.
- **Hierarchy:** `NONE`.
- **Schema:**
  ```json
  {
    "type": "object",
    "required": ["days_from", "is_default", "sicr_stage"],
    "properties": {
      "days_from":  {"type": "integer", "minimum": 0},
      "days_to":    {"type": ["integer", "null"], "minimum": 0,
                     "description": "null = unbounded (например, 90+)"},
      "is_default": {"type": "boolean"},
      "sicr_stage": {"type": "string", "enum": ["SICR_1", "SICR_2", "SICR_3"]}
    },
    "additionalProperties": false
  }
  ```
- **Seed (пример заказчика):**
  ```
  BUCKET_0_30     days_from=0   days_to=30    is_default=false  sicr_stage=SICR_1  label_ru="Без просрочки или до 30 дней"
  BUCKET_30_90    days_from=30  days_to=90    is_default=false  sicr_stage=SICR_2  label_ru="Просрочка 30–90 дней"
  BUCKET_90_PLUS  days_from=90  days_to=null  is_default=true   sicr_stage=SICR_3  label_ru="Просрочка свыше 90 дней (дефолт)"
  ```
- **Domain validators:**
  - `bucket_intervals_disjoint`: интервалы `[days_from, days_to)` не пересекаются
    и покрывают `[0, ∞)` без пропусков. `null` в `days_to` допускается **только
    у последней корзины** (по `days_from`).
  - `default_bucket_exists`: ровно одна корзина с `is_default=true`,
    и она с максимальным `days_from`.

#### 2.2.4. PD/LGD/EAD (опционально в первой итерации)

Заказчик подтвердил, что в первую очередь нужны transition + buckets.
PD/LGD/EAD-матрицы — отдельные CodeSet'ы той же механики (composite key
по `(segment, rating, horizon)` / `(segment, collateral_type)` /
`(product, exposure_class)`). Их схемы можно зашить в `rdmmesh-spec/`, но
seed не наполнять — Schema Designer создаст в UI по факту.

### 2.3. Дискриминатор kind — выбор Slice C: tags (а не отдельная колонка)

**Реализация Slice C** использует существующее поле `code_set.tags` (`text[]`)
с конвенцией `kind:<type>`:

| CodeSet | tags |
|---|---|
| rating_scale | `["kind:rating_scale", "credit_risk"]` |
| delinquency_buckets | `["kind:delinquency_buckets", "credit_risk"]` |
| rating_transition_matrix | `["kind:transition_matrix", "credit_risk"]` |

UI читает `codeset.tags?.includes('kind:transition_matrix')` и активирует
pivot-вью; backend в Slice C ничего не знает про `kind` — поведение полностью
управляется UI поверх существующих данных.

**Почему не отдельная колонка `catalog.code_set.kind`** (как было в исходном
драфте §2.3): требует миграции `V015`, изменения `code-set.json` JSON Schema,
прогона `make codegen` (Java POJO regen), правки `CodeSetDao.COLUMNS`,
`CodeSetRow` record, `insert()` SQL/method, `NewCodeSetRequest`,
`CatalogService.NewCodeSet` маппинга, TS-типа в `src/api/types.ts` — 9 точек
плумбинга. Для Slice C это оверкилл; tags-based вариант даёт идентичную
функциональность в UI без backend-правок.

**Когда переходить на отдельную колонку.** Если понадобится:
1. SQL-фильтр `WHERE kind = 'transition_matrix'` в distribution-индексе (сейчас
   тэги в `text[]` — фильтр через `ANY`, индекс GIN, чуть медленнее).
2. CHECK constraint, чтобы исключить `kind:foo` (опечатки).
3. Полный контракт OpenAPI с enum-валидацией.

Тогда — отдельный эпик `E19.1` с миграцией + codegen + DAO. Slice C
работает без этого.

### 2.3-legacy. (Устаревшее: миграция `catalog.code_set` — колонка `kind`)

Сохраняю для истории, если решим вернуться (см. §2.3 выше — выбран
tags-based вариант).

`bootstrap/sql/migrations/catalog/V015__codeset_kind.sql`:

```sql
ALTER TABLE catalog.code_set
    ADD COLUMN kind text NOT NULL DEFAULT 'flat'
        CHECK (kind IN ('flat', 'transition_matrix', 'pd_matrix',
                         'lgd_matrix', 'ead_matrix'));

COMMENT ON COLUMN catalog.code_set.kind IS
  'UI/validator hint: flat (default) | transition_matrix | pd_matrix | lgd_matrix | ead_matrix.
   Семантика — backend domain-validators (E19) и UI-вью.';
```

Замечания:
- `flat` — обратная совместимость, существующие CodeSet'ы остаются на нём.
- Список enum — закрытый (CHECK), расширяется новой миграцией при появлении
  новых типов (например, `correlation_matrix`).
- **Не** добавляем `kind` в JSON Schema CodeSetSchema'ы — это **свойство
  CodeSet'а** (заголовок), а не его CodeItem'ов.

### 2.4. Миграция `authoring` — нет

В `authoring` (`code_item`) ничего не меняется — composite key, JSONB-атрибуты
и cross-codeset refs уже работают (E4 + E13.2). E19 — это только новая
семантика **поверх** существующих таблиц.

---

## 3. Контракт REST

### 3.1. `POST /api/v1/versions/{versionId}/items/bulk-xlsx` — расширение

Существующий эндпоинт E15 `bulkXlsx` уже принимает long-формат. Для pivot-матриц
добавляем **query-параметры**:

```
POST /api/v1/versions/{versionId}/items/bulk-xlsx
     ?layout=pivot                        # pivot | long (default: long, как E15)
     &horizon=1Y                          # обязательно если layout=pivot и codeset.kind=transition_matrix
     &row_residual_policy=implicit_default  # implicit_default | strict (default: implicit_default)
     &sheet=Migrations_1Y                 # опционально, default = первый лист
```

- `layout=long` — поведение E15 без изменений (обратная совместимость).
- `layout=pivot` — XLSX парсится как матрица: первая колонка = `from_rating`,
  первая строка = `to_rating`, остальные ячейки = `probability`. Тройки
  `(from, to, horizon)` собираются из координат + query-param `horizon`.
- `row_residual_policy=implicit_default` — если сумма строки < 1, дописывается
  ячейка `(from, D, horizon) = 1 − Σ`. Шкала `rating_scale` обязана содержать
  `is_absorbing=true` грейд (иначе — 422 `MissingAbsorbingGrade`).
- `row_residual_policy=strict` — любая строка с суммой ≠ 1 ± 1e-3 →
  422 `RowNotStochastic` с пер-row отчётом.
- Роль/предусловия — как у E15: `RDM_AUTHOR|RDM_ADMIN`, только в DRAFT,
  атомарно.
- Ответ — тот же `BulkResult` (`applied/rejected`), плюс новое поле
  `implicit_default_added: int` (сколько ячеек дописано).

**Альтернативный API-дизайн** (см. §10 open question): отдельный эндпоинт
`POST .../items/bulk-matrix` вместо query-параметров. Решить при реализации.

### 3.2. `POST /api/v1/versions/{versionId}/validate` — domain-validators

Существующий эндпоинт валидации (E4 §1.8) расширяется новыми ошибками:
- `RowNotStochastic` — для transition_matrix.
- `AbsorbingStateInconsistent` — для transition_matrix.
- `BucketIntervalsOverlap` / `BucketsDoNotCoverAxis` / `MultipleDefaultBuckets`
  — для delinquency_buckets.

Коды ошибок REST — без новых HTTP-статусов (всё 422 в общем reject-конверте,
как у `field`-ошибок E4).

### 3.3. `GET /api/v1/rdm/credit_risk/rating_transition_matrix/items` — расширение consumer-эндпоинта

Существующий read-only distribution-эндпоинт (E8) уже отвечает long-форматом.
Добавляем `format=pivot`:

```
GET /api/v1/rdm/credit_risk/rating_transition_matrix/items
    ?version=published
    &horizon=1Y                    # обязательно для format=pivot
    &format=long|pivot|csv|xlsx    # default long
    &as_of=2026-05-26
```

- `format=pivot` → JSON `{rows: [...], cols: [...], matrix: [[...], ...]}`,
  с упорядочиванием по `rating_scale.order`.
- Прочие форматы (`csv`/`xlsx`) — long-формат с теми же колонками, что у E15
  export (`key_parts, attributes, ...`). Pivot-XLSX-экспорт — opt-in,
  §10 open question.

---

## 4. Изменения в `authoring` и новые валидаторы

### 4.1. Новый парсер `MatrixPivotSheetParser`

- Пакет: `org.rdmmesh.authoring.internal.xlsx`.
- Конструктор: `(CodeSetSchema, RatingScale scale, String horizon,
  RowResidualPolicy policy)`.
- API: `BulkResult parse(InputStream xlsxStream, String sheetName)`.
- Стримовое чтение через `fastexcel-reader` (как E15 §1.2).
- Алгоритм:
  1. Прочитать первый ряд → headers колонок (`to_rating` labels). Сматчить
     каждый header на `rating_scale.code` (case-sensitive; неизвестный →
     reject `UnknownRating` для всей загрузки).
  2. Для каждой следующей строки: первая ячейка = `from_rating` (match на
     `rating_scale`), остальные — `probability`.
  3. Сформировать triples `(from, to, horizon, probability)` через
     `CsvBulkParser.buildRow` (DRY, E15 §1.2 — общий row-builder).
  4. Если `policy=IMPLICIT_DEFAULT` и сумма строки < 1 − 1e-3 → дописать
     `(from, absorbing_grade, horizon, 1 − Σ)`. Увеличить счётчик
     `implicit_default_added`.
  5. Если `policy=STRICT` и сумма ≠ 1 ± 1e-3 → reject `RowNotStochastic`.
- **Не** валидирует absorbing-consistency на этапе парсинга — это
  domain-validator на этапе `validate` (§4.2). Парсер только нормализует
  данные в triples и делает residual-fixup.

### 4.2. Domain-validators (общий механизм)

Текущая валидация в `authoring` — это JSON Schema по `code_set_schema.attributes`.
Custom-валидаторы поверх JSON Schema нужны новые. Дизайн:

- Новый интерфейс в `rdmmesh-api`:
  ```java
  public interface CodeSetDomainValidator {
      String name();                                    // "row_stochastic" и т.д.
      boolean appliesTo(CodeSet cs);                    // например cs.kind() == "transition_matrix"
      List<ValidationError> validate(VersionSnapshot snapshot);
  }
  ```
- Реализации в `rdmmesh-authoring/internal/validators/`:
  - `RowStochasticValidator` (transition_matrix).
  - `AbsorbingStateValidator` (transition_matrix).
  - `BucketIntervalsValidator` (delinquency_buckets).
  - `DefaultBucketValidator` (delinquency_buckets).
- Регистрация — через DI в `AuthoringModule.build(...)`. Список
  ServiceLoader-style, чтобы добавление нового валидатора не трогало wiring.
- Вызов — в `AuthoringService.validate(versionId)` после JSON-Schema-валидации,
  до записи в `BulkResult`. Ошибки идут тем же конвертом
  (`BulkResult.rejected[].field = "domain:<validator_name>"`).
- **ArchUnit:** валидаторы могут импортировать только `rdmmesh-api` и
  внутренний `authoring`. Никаких cross-cuts в catalog/workflow.

### 4.3. Atomicity и порядок

- Парсер сначала собирает ВЕСЬ triple-set, потом передаёт в `bulkUpsertJson`
  (E15 §1.2 — атомарно). Никаких partial-applies.
- Domain-validators запускаются на финальном snapshot'е draft'а
  (после bulk-операции) — это поведение E4 (отдельный шаг `validate`), не
  меняем.

---

## 5. Размещение по модулям (ArchUnit)

| Что | Модуль | Почему |
|---|---|---|
| `MatrixPivotSheetParser`, `RowResidualPolicy` enum | `authoring` | парсинг draft'а — domain authoring (E4/E15) |
| `CodeSetDomainValidator` интерфейс | `rdmmesh-api` | межмодульный контракт |
| 4 валидатора (stochastic, absorbing, buckets, default-bucket) | `authoring` | runtime-валидация CodeItem'ов |
| Колонка `catalog.code_set.kind` + DAO update | `catalog` | владелец metadata CodeSet'а |
| `format=pivot` в distribution | `distribution` | read-only consumer (E8) |
| `RatingTransitionPivotView`, `DelinquencyBucketsView` | `rdmmesh-ui` | E11 |
| Seed-данные `rating_scale`, `delinquency_buckets` | `bootstrap/seed/credit_risk/` | dev/smoke |

ArchUnit-правила (без изменений):
- `authoring` не импортирует `catalog..` напрямую — только через
  `CatalogPort` (SPEC §3.3).
- Валидаторы могут трогать `code_item`/`code_set` только через
  read-DAO `authoring`.
- `distribution` остаётся read-only (никаких write в БД).

---

## 6. Изменения UI (E11)

### 6.1. Новый компонент `RatingTransitionPivotView`

Файл: `rdmmesh-ui/src/components/credit-risk/RatingTransitionPivotView.tsx`.

Активация: на странице `VersionPage` (E11.2b), если `codeset.kind ===
'transition_matrix'`, рядом со стандартным grid-вью показывается toggle
**«Pivot / Long»** (default — Pivot). Long-вью — существующий
`CodeItemEditor` (E11.2b).

Pivot-вью:
- Заголовок: AntD `Select` `horizon` — `[1M, 3M, 6M, 1Y, 3Y, 5Y]` (фильтрует
  CodeItem'ы по `key_parts.horizon`).
- Сетка `rating_scale.order × rating_scale.order` (включая absorbing-grade).
- Каждая ячейка — `(from, to, horizon)` triple. Значение — `attributes.probability`,
  inline-edit с числовой маской. Bulk-paste из Excel-буфера — через
  `onPaste` (parse TSV/CSV, mapping в triples, batched
  `apiMutations.upsertItems`).
- Подсветка:
  - Строка/колонка absorbing-grade — серым фоном; D→D=1.000 — bold.
  - Сумма строки в дополнительной колонке `Σ` (info-only, формат
    `0.000` с цветом: зелёный 0.999–1.001, красный иначе).
- Кнопка **«Import XLSX (pivot)»** — открывает `BulkImportModal` E15 с
  предзаполненным `layout=pivot&horizon=<current>&row_residual_policy=
  implicit_default`. Switch `implicit_default ⇄ strict` в модалке.
- Кнопка **«Switch to Long»** — переход в стандартный grid.

### 6.2. Новый компонент `DelinquencyBucketsView`

Файл: `rdmmesh-ui/src/components/credit-risk/DelinquencyBucketsView.tsx`.

Активация: на `VersionPage`, если `codeset.code === 'delinquency_buckets'`
(не через `kind`, потому что это flat-CodeSet с особой семантикой). Стандартный
grid + визуализация числовой оси:

```
0          30           90        ∞
 ├─────────┼────────────┼─────────▶
   BUCKET     BUCKET      BUCKET
   _0_30      _30_90      _90_PLUS (default ★)
```

Drag-зоны для редактирования границ — V2, в первой итерации только read-only
визуализация + редактирование атрибутов в grid'е снизу.

### 6.3. `BulkImportModal` — новый таб «Pivot Matrix»

Расширяем существующий `BulkImportModal` (E15 §1.4): три таба → четыре.
Виден только для `codeset.kind === 'transition_matrix'`.

Поля:
- `Upload.Dragger` для `.xlsx`.
- `horizon` — `Select`, обязателен.
- `row_residual_policy` — `Radio.Group`:
  - **Add implicit Default column (PD = 1−Σ)** (рекомендовано — default).
  - **Strict — fail import**.
- `sheet` — `Input`, опционален.

Submit → `POST .../items/bulk-xlsx?layout=pivot&horizon=...&row_residual_policy=...`.
Ответ — `ResultPanel` (как E15), плюс новая строка
«Дописано ячеек Default: {N}» если был `implicit_default_added > 0`.

### 6.4. i18n

Новые ключи в `ru.json`/`en.json`:
- `creditRisk.pivot.horizon`, `creditRisk.pivot.rowSum`,
  `creditRisk.pivot.absorbing`.
- `creditRisk.buckets.axisLabel`, `creditRisk.buckets.defaultMarker`.
- `bulk.pivot.layout`, `bulk.pivot.implicitDefault`,
  `bulk.pivot.strict`, `bulk.pivot.implicitDefaultAdded`.

---

## 7. Bootstrap: домен и seed

### 7.1. Создание домена `credit_risk`

В dev/smoke — через E18 bootstrap REST:

```bash
curl -sS -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"om_domain_id":"<uuid>","name":"credit_risk","display_name":"Credit Risk",
       "label_ru":"Кредитный риск","label_en":"Credit Risk"}' \
  http://localhost:8080/api/v1/admin/domains
```

В prod — после первого webhook'а E7 на `entityType=domain` из OM
(когда команда Data Governance заведёт домен в OpenMetadata).

### 7.2. Seed CodeSet'ов

Новый каталог `bootstrap/seed/credit_risk/`:
- `rating-scale.json` — 5 строк, как §2.2.1 seed.
- `delinquency-buckets.json` — 3 строки, как §2.2.3 seed.
- `rating-transition-matrix.json` — пример из §1 (4×4 + дописанная D-колонка
  + строка D=absorbing) для горизонта `1Y`. Используется в smoke + как
  test fixture.

Загрузка — расширение `scripts/seed-demo.sh`: после создания домена создаются
CodeSet'ы и загружаются seed-items.

### 7.3. Steward/Owner для домена

В `bootstrap/seed/domain-role-directory.example.json` (E17 §7) добавить записи
для `credit_risk`:
- `STEWARD` — `dev-steward-credit` (или переиспользовать `dev-steward`).
- `BUSINESS_OWNER` — `dev-owner-credit`.

Иначе E17 submit на новых CodeSet'ах не пройдёт (тройка не найдена в
`domain_role_directory` → 409).

---

## 8. Что НЕ входит в E19 (намеренная граница)

- **Markov-аналитика** — расчёт PD на длинном горизонте через `P^n`,
  стационарного распределения, time-to-default. Это работа потребителя
  (Risk-движок, BI), не RDM. RDM хранит и публикует матрицу, ничего над ней
  не считает.
- **Калибровка матриц** — bootstrap-выборка, smoothing, Bayesian update.
  Это методология заказчика, поставляется как готовая матрица.
- **Макросценарии** (baseline/adverse/optimistic) — упомянуты в SPEC §1.3 как
  отдельный класс справочников Risk-домена, в E19 не входят (отдельный CodeSet,
  не матричный — обычный flat).
- **Корреляционные матрицы** (для портфельного PD) — это уже не stochastic
  (диагональ = 1, симметрия), отдельный `kind='correlation_matrix'` с другим
  набором валидаторов — отдельный эпик.
- **Drag-edit границ корзин** в `DelinquencyBucketsView` — V2.
- **Автоматическое сравнение «новая матрица vs наблюдаемые миграции
  за период»** — это backtesting, не RDM.
- **GUI evolution-просмотр** (slider по `as_of`, чтобы посмотреть, как
  матрица менялась) — bitemporal-API уже всё умеет, но UI-виджет — V2.

---

## 9. План smoke (для агента-реализатора)

```bash
make up
TADM=$(KC_USER=dev-admin make kc-token)
TAUT=$(KC_USER=dev-author make kc-token)
TST=$(KC_USER=dev-steward-credit make kc-token)
TOWN=$(KC_USER=dev-owner-credit make kc-token)

# 0. Bootstrap домен credit_risk (E18) + reload domain-role-directory (E17)
curl -sS -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d '{"om_domain_id":"...","name":"credit_risk","display_name":"Credit Risk"}' \
  http://localhost:8080/api/v1/admin/domains
curl -sS -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d @bootstrap/seed/domain-role-directory.example.json \
  http://localhost:8080/api/v1/admin/domain-role-directory:reload

# 1. Создать rating_scale CodeSet, draft v1.0.0, залить seed (5 грейдов)
# 2. Submit → Steward credit_risk approve → Owner approve → Published
RS_VID=...  # version_id опубликованного rating_scale

# 3. Создать rating_transition_matrix CodeSet с kind=transition_matrix
RT_VID=...  # version_id draft'а

# 4. Импорт 4×4 матрицы заказчика в layout=pivot, IMPLICIT_DEFAULT
curl -sS -X POST -H "Authorization: Bearer $TAUT" \
     -H 'Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet' \
     --data-binary @bootstrap/seed/credit_risk/transition-1Y.xlsx \
     "http://localhost:8080/api/v1/versions/$RT_VID/items/bulk-xlsx?layout=pivot&horizon=1Y&row_residual_policy=implicit_default"
#   → 200, applied=20 (4×4 + 4 дописанных D + строка D=5), implicit_default_added=4

# 5. Validate → row_stochastic + absorbing_state_consistency OK
curl -sS -X POST -H "Authorization: Bearer $TAUT" \
     "http://localhost:8080/api/v1/versions/$RT_VID/validate"
#   → 200, всё green

# 6. Negative: тот же импорт с row_residual_policy=strict → 422 RowNotStochastic
# 7. Negative: матрица без absorbing-grade в rating_scale, IMPLICIT_DEFAULT
#    → 422 MissingAbsorbingGrade

# 8. Submit → Steward credit_risk approve → Owner approve → Published
# 9. Distribution: GET pivot
curl -sS -H "Authorization: Bearer $TAUT" \
     "http://localhost:8080/api/v1/rdm/credit_risk/rating_transition_matrix/items?version=published&horizon=1Y&format=pivot"
#   → {rows:[AAA,A,BB,B,D], cols:[AAA,A,BB,B,D], matrix:[[0.880, ...], ...]}

# 10. То же для delinquency_buckets: создать → импорт long из CSV (3 строки) →
#     validate (bucket_intervals_disjoint, default_bucket_exists) → publish

# 11. UI smoke: открыть /codesets/.../versions/$RT_VID → должна быть кнопка
#     "Pivot" по умолчанию, видна 5×5 матрица, переключатель горизонта,
#     Σ-колонка зелёная.

# БД-проверки
docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -c \
 "SELECT kind, code FROM catalog.code_set WHERE code IN ('rating_scale','rating_transition_matrix','delinquency_buckets');"
docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -c \
 "SELECT key_parts, attributes->>'probability' FROM authoring.code_item
   WHERE version_id='$RT_VID' AND key_parts->>2='1Y' ORDER BY key_parts;"
```

> Памятка: `jq` в окружении нет — python3-инлайн для парсинга токена
> (E5 §3.1). Docker доступен — **реально прогнать `make up` и smoke**.

---

## 10. Открытые вопросы (для команды банка / архитектора)

1. **Отдельный эндпоинт vs query-параметры.** §3.1 предлагает расширить
   `bulk-xlsx` query-параметрами `layout`/`horizon`/`row_residual_policy`.
   Альтернатива — отдельный `POST .../items/bulk-matrix` с явным телом
   `{layout, horizon, policy, content_b64}`. Trade-off: query-параметры — меньше
   API-поверхности, явный эндпоинт — чище OpenAPI и не тянет cross-feature
   coupling в один resource. **Рекомендация:** query-параметры (E15 уже
   накопил такое поведение).
2. **`row_stochastic` tolerance.** Жёстко 1e-3 или конфигурируемо
   per-CodeSet (атрибут CodeSetSchema'ы)? Внутренние данные могут быть
   округлены до 3 знаков (как пример заказчика — суммы 0.999/0.998/0.980/0.910),
   и тогда 1e-3 пограничен. **Рекомендация:** дефолт 1e-3, override через
   `CodeSetSchema.metadata.row_stochastic_tolerance: number` (новое поле в
   schema-схеме). Заказчику показать пример заказчика — устроит ли 1e-3.
3. **`horizon` как enum vs CodeSet.** Сейчас `horizon` — фиксированный enum
   `[1M..5Y]` в JSON Schema. Альтернатива — отдельный CodeSet `risk_horizons`
   с cross-ref. Trade-off: enum — проще валидация и UI; CodeSet — расширяемо
   без релиза кода. **Рекомендация:** enum для MVP (заказчик подтвердил
   фиксированный набор), CodeSet — V2 если понадобятся custom-горизонты.
4. **Pivot XLSX-экспорт** (`format=pivot` в `export?format=xlsx`). Сейчас
   §3.3 говорит только про JSON-pivot. Внутренние потребители часто хотят
   экспортнуть в Excel «в том же виде, что заливали» — реалистично, но это
   ещё один writer-режим. **Рекомендация:** добавить в первую итерацию
   (заказчик импортирует pivot — захочет и экспорт).
5. **Связь `delinquency_buckets` ↔ `rating_scale`.** Логически
   `BUCKET_90_PLUS.is_default=true` коррелирует с `rating_scale` где есть
   absorbing-grade. Должен ли быть валидатор «если в `rating_scale` есть
   absorbing-grade, то существует published `delinquency_buckets` с default-
   bucket'ом»? Это уже cross-codeset бизнес-правило, может выходить за scope.
   **Рекомендация:** не делать в E19; bracket для отдельного эпика governance-rules.
6. **PD/LGD/EAD в первой итерации.** §2.2.4 говорит «опционально». Заказчик
   подтвердил приоритет transition + buckets. PD-матрица — следующий шаг
   (та же механика). Подтвердить порядок.
7. **Прав на чтение `credit_risk`.** Сейчас distribution-эндпоинты read-only
   для любого authenticated (E8). Кредитные матрицы — sensitive? Если да —
   нужен role-gate `RDM_CREDIT_RISK_CONSUMER` или per-domain ACL. Это
   расширение E8 (отдельный эпик), не делается в E19, но **обязательно
   подтвердить** до прод-релиза.
8. **`as_of_obs` атрибут.** §2.2.2 предлагает опциональное поле «дата
   наблюдения, по которой оценена матрица». Альтернатива — хранить в
   `code_set_version.metadata` (одно значение на всю версию). Per-cell vs
   per-version. **Рекомендация:** per-version, проще модель.

---

## 11. Версия документа

- **0.1** — 2026-05-26. Спецификация эпика E19 (Credit Risk Matrices:
  rating_scale, rating_transition_matrix, delinquency_buckets + опционально
  PD/LGD/EAD). Реализация не начата. Решения заказчика, зафиксированные в §1:
  `IMPLICIT_DEFAULT` по умолчанию + явный switch (п.6), отдельный домен
  `credit_risk` (п.3), полный набор горизонтов `{1M, 3M, 6M, 1Y, 3Y, 5Y}`
  (п.5), pivot-вью «таблица сама» (п.4). Автор: Claude Opus 4.7 на запрос
  пользователя ismailova.amina51@gmail.com.
