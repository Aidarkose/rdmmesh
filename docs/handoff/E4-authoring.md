# Handoff — Эпик E4 (Authoring)

> **Аудитория документа.** AI-агенты и инженеры, подключающиеся к проекту после E4. Документ самодостаточен — переписки и контекста предыдущей сессии у вас нет, всё что нужно — здесь, в [`SPEC.md`](../../SPEC.md), [`E1-foundation.md`](E1-foundation.md), [`E2-identity.md`](E2-identity.md) и [`E3-catalog.md`](E3-catalog.md).
>
> **Дата handoff'а.** 2026-05-04.
> **Состояние:** E4 закрыт по содержанию SPEC §5.1. End-to-end smoke с Keycloak JWT покрывает CRUD versions/items, bulk JSON+CSV, optimistic-lock, diff. `make verify` зелёный (50 тестов: 31 unit'а в authoring + 8 JwtValidator + 11 ArchUnit).
> **Следующий эпик:** E5 (Workflow). Указатели — в §5.

---

## 0. TL;DR за 30 секунд

- Реализован модуль `rdmmesh-authoring` (SPEC §3.5):
  - REST: создание/удаление DRAFT-версий; CRUD CodeItem'ов внутри DRAFT с optimistic-lock; bulk-upsert JSON и CSV (atomic); diff между версиями.
  - Bitemporal-поля (`effective_*`, `system_*`) и `row_version` идут до самой схемы — авторские mutation'ы инкрементируют `row_version` и пишут в одной транзакции closure-table.
  - Валидация `attributes` через `com.networknt:json-schema-validator` против активной CodeSetSchema. Кэш скомпилированных схем — Caffeine-style (ConcurrentHashMap).
  - Diff = SQL-функция `authoring.code_item_diff_base` (миграция V021) + Java-pass для `changed_fields` и MOVED-эвристики (parent_key-only-change).
- Добавлен `CatalogReadPort` в `rdmmesh-api` — узкий read-side контракт catalog'а; реализация — `CatalogReadAdapter` в catalog. Так authoring видит активную CodeSetSchema без cross-module imports.
- ArchUnit-rule `modules_do_not_import_internals` переписан корректно: 8 правил вида «`X.internal..` доступен только из `X..` + `app..` + api/spec». Старая формулировка ошибочно блокировала intra-module `resource → internal.service` (в E3 правило было strict, но проблема не вылезла — handoff E3 §1.6 содержит ошибочное упоминание о работающей strict-проверке).
- Flyway теперь работает в `outOfOrder=true`: каждый bounded context добавляет миграции в свою серию (V0X1, V0X2, …) поверх уже-применённых V0X0; без флага V021 не наезжает на applied V070.
- Composite key в URL'ах — `base64url(JSON.stringify(keyParts))`. См. `KeyEncoding`.
- Optimistic lock — обязательное `expected_row_version` в PATCH; конфликт = 409 Conflict.

---

## 1. Что сделано

### 1.1. Миграция V021

`bootstrap/sql/migrations/authoring/V021__code_item_diff.sql` создаёт SQL-функцию `authoring.code_item_diff_base(from uuid, to uuid)` которая возвращает rowset `(op, key_parts, before_doc, after_doc)`. `op ∈ {ADDED, REMOVED, CHANGED, UNCHANGED}`. Java-калькулятор фильтрует `UNCHANGED`, считает `changed_fields` и эвристически перекладывает CHANGED → MOVED, если изменился только `parent_key`/`parent_ref`.

Почему функция, а не view: SQL-views не принимают параметры в Postgres — пришлось бы делать view с join по двум versions через где-clause; функция-rowset элегантнее.

### 1.2. CatalogReadPort

`rdmmesh-api/src/main/java/bank/rdmmesh/api/port/CatalogReadPort.java`:

```java
Optional<CodeSetSnapshot>      findCodeSet(UUID codesetId);
Optional<CodeSetSchemaSnapshot> currentSchema(UUID codesetId);
Optional<CodeSetSchemaSnapshot> schemaByVersion(UUID codesetId, int schemaVersion);
```

Реализация — `CatalogReadAdapter` в `bank.rdmmesh.catalog.internal`. Wire-up в `RdmmeshApplication.run()`:

```java
CatalogReadPort catalogReadPort = CatalogModule.buildReadPort(jdbi);
```

**Зачем `schemaByVersion`, а не только `currentSchema`?** Active CodeSetSchema **фиксируется** в `code_set_version.schema_version` при создании draft'а. Дальше валидация attributes идёт по этому snapshot'у — даже если Schema Designer выпустит новую schema-revision посреди работы Author'а, текущий draft валидируется по своей. Без этой инвариантности изменение схемы ломало бы in-flight draft'ы.

### 1.3. Структура модуля `rdmmesh-authoring`

```
rdmmesh-authoring/src/main/java/bank/rdmmesh/authoring/
├── AuthoringModule.java                ← composition factory: build(jdbi, catalogReadPort, json) → Resources
├── resource/
│   ├── CodeSetVersionResource.java     ← /versions/by-codeset/{id}, /versions/{id}
│   ├── CodeItemResource.java           ← /versions/{id}/items/...
│   ├── VersionDiffResource.java        ← /versions/{id}/diff?from=...
│   └── CodeItemDto.java                ← wire DTO (см. §1.7)
└── internal/
    ├── service/AuthoringService.java   ← бизнес-логика + транзакции
    ├── dao/
    │   ├── CodeSetVersionDao.java
    │   ├── CodeItemDao.java
    │   ├── CodeItemClosureDao.java     ← rebuild через WITH RECURSIVE на каждый write
    │   └── CodeItemDiffDao.java        ← обёртка над V021 SQL-функцией
    ├── validation/AttributesValidator.java   ← networknt JSON Schema, draft-07
    ├── diff/DiffCalculator.java        ← op + changed_fields + MOVED-эвристика
    ├── csv/CsvBulkParser.java          ← jackson-csv, формат описан в Javadoc
    ├── KeyEncoding.java                ← base64url(JSON) для composite key в URL
    ├── SemVer.java                     ← parse + bump (major/minor/patch)
    └── AuthoringMappers.java           ← row → DTO/spec POJO
```

### 1.4. REST-эндпоинты

| URL | Метод | Роль | Что делает |
|---|---|---|---|
| `/versions/by-codeset/{id}` | GET | любой auth | список версий CodeSet'а |
| `/versions/by-codeset/{id}` | POST | author/admin | создать DRAFT (опц. из последней published) |
| `/versions/{id}` | GET | любой auth | одна версия |
| `/versions/{id}` | DELETE | author/admin | удалить DRAFT |
| `/versions/{id}/items` | GET | любой auth | paginated items (`page`, `size`) |
| `/versions/{id}/items` | POST | author/admin | добавить item (валидация attributes) |
| `/versions/{id}/items/{key}` | GET | любой auth | lookup по composite key (base64url(JSON)) |
| `/versions/{id}/items/{itemId}` | PATCH | author/admin | обновить (требует `expected_row_version`) |
| `/versions/{id}/items/{itemId}` | DELETE | author/admin | удалить item (только в DRAFT) |
| `/versions/{id}/items/bulk` | POST | author/admin | bulk JSON upsert (atomic) |
| `/versions/{id}/items/bulk-csv` | POST | author/admin | bulk CSV upsert (`text/csv`) |
| `/versions/{id}/diff?from={fromId}` | GET | любой auth | diff между двумя версиями |

> **URL note.** SPEC §3.5 описывает `POST /codesets/{id}/versions`, но в Jersey два root-resource'а (`CodeSetResource @Path("/codesets")` + здесь) с пересекающимися путями ведут к 404 — та же причина, по которой E3 свернул `/domains/{id}/codesets` в `/codesets/by-domain/{id}`. Поэтому здесь — `/versions/by-codeset/{id}`. Если в E11 (UI) понадобится backwards-compat alias — добавится тонкий wrapper.

### 1.5. Optimistic lock

`code_item.row_version` — `int`, `DEFAULT 0`. На каждом UPDATE инкрементируется. PATCH принимает обязательное `expected_row_version` в body (не header'ом — Dropwizard и так не страдает энтерпрайзностью). DAO-метод `updateInDraft` зашит так:

```sql
UPDATE authoring.code_item SET ..., row_version = row_version + 1
 WHERE id = :id AND row_version = :expectedRowVersion AND EXISTS (
   SELECT 1 FROM authoring.code_set_version v
    WHERE v.id = authoring.code_item.version_id AND v.status = 'DRAFT')
```

0 affected rows → service бросает `OptimisticLockException` → resource отдаёт 409 Conflict. Один и тот же запрос дважды — второй получит 409 (потому что после первого `row_version` уже не тот). Это поведение проверено в smoke (см. §2).

### 1.6. Валидация attributes

`AttributesValidator` использует `com.networknt:json-schema-validator` 1.5.5, draft-07 (совпадает с `rdmmesh-spec/schema/*.json`). При невалидной payload:
- Resource возвращает **422 Unprocessable Entity** (не 400, потому что синтаксис верный — некорректна семантика).
- Тело — список ошибок в формате `$.path: <message>` (от networknt).

Сама схема, если синтаксически битая — НЕ ломает 500'кой; валидатор возвращает `["Schema is invalid: ..."]` и resource отдаёт 422 ровно с этим сообщением. Это полезно для Schema Designer'а — UI покажет понятную ошибку.

### 1.7. Свой `CodeItemDto` вместо сгенерированного `CodeItem`

Тот же паттерн, что E3 §1.4. Сгенерированный jsonschema2pojo'ём `CodeItem` имеет вложенный `Attributes` POJO, который не принимает произвольные fields. У нас attributes — это **намеренно** свободный JSON (валидируется не по static schema, а по dynamic CodeSetSchema), поэтому собственный record с `Map<String, Object>` подходит лучше.

### 1.8. CSV-формат bulk import'а

Колонки (header-based CSV):

| Колонка | Обязательная | Семантика |
|---|---|---|
| `key_parts` | да | JSON-array as string (`["KZ"]` или `["RETAIL","BB","12M"]`); single-string без скобок интерпретируется как одиночный ключ |
| `parent_key` | нет | JSON-array; intra-codeset hierarchy |
| `label_ru`, `label_en`, `description_ru`, `description_en` | нет | localized labels |
| `attributes` | нет | JSON-object as string |
| `attr.<name>` | нет | значение этого attribute (см. ниже про коэрцию) |
| `order_index`, `status` | нет | очерёдность + ACTIVE/RETIRED |
| `effective_from`, `effective_to` | нет | ISO date (YYYY-MM-DD) |

**Коэрция значений в `attr.*` колонках.** Только {`true`/`false` → Boolean}. Числа намеренно НЕ распознаются — иначе ломается enum-валидация типа `stage ∈ {"1","2","3"}`. Для чисел — `attributes` JSON-колонка.

Atomicity: вся пачка — одна транзакция. Любая ошибка → транзакция rollback'ится, отдаётся 422 со списком errors, ничего не записано.

### 1.9. Composite key в URL'ах

`KeyEncoding.encode(["RETAIL","BB","12M"])` → `WyJSRVRBSUwiLCJCQiIsIjEyTSJd` (base64url, без padding). `decode` обратно в список. URL: `/versions/{vid}/items/WyJSRVRBSUwiLCJCQiIsIjEyTSJd`.

Альтернативные разделители (`-`, `~`, etc.) небезопасны: ключевые части могут содержать любые символы. Base64url — детерминистично, URL-safe, обратимо.

### 1.10. Ремонт ArchUnit-rule `modules_do_not_import_internals`

В E3 правило было сделано strict с формулой:

```
noClasses().that().resideInAPackage("bank.rdmmesh.(catalog|...)..")
   .and().resideOutsideOfPackages("...internal..")
   .should().dependOnClassesThat().resideInAnyPackage("...internal..")
```

Эта формула запрещает **любому** non-internal классу (включая внутри своего же модуля!) импорт internal — то есть catalog.resource → catalog.internal.service ловится как нарушение. Странно, что E3 декларирует verify зелёным; вероятнее всего тест прогонялся без этого правила (или прогонялся, но фикс не дошёл до коммита).

E4 переписывает правило корректно: 8 отдельных правил вида «`X.internal..` доступен только из `X..` + `app..` + api/spec». Внутри-модульные `resource → internal.service` теперь легальны (это тот самый паттерн). Cross-module internal access — запрещён. Реализация — приватный helper `internalSliceUsedOnlyBy(String moduleName)`. Снять `allowEmptyShould(true)` с конкретного `<module>_internal_only_used_by_<module>` нужно, как только в этом модуле появится первый класс в `internal..` (для empty audit/distribution/publishing/workflow это справедливо до E10/E8/E6/E5).

### 1.11. Flyway: `outOfOrder=true`

В `RdmmeshApplication.runFlyway`:

```java
.outOfOrder(true)   // V021 после уже-применённой V070
```

Без этого Flyway в strict-mode отказывался применить V021 поверх V070 (там, что 21<70). Поскольку каждый bounded context владеет своей серией миграций (V0X0, V0X1, ...) и они логически независимы — это не аномалия, а ожидание архитектуры. Пишется в Flyway-warn, но в `flyway_schema_history` всё корректно. Альтернатива (нумеровать V071, V072, ...) ломает группирующую конвенцию из E1 §1.3.

---

## 2. Smoke (то, что прошло на 2026-05-04)

```bash
make up                                         # postgres+keycloak+rdmmesh-service healthy
TOKEN=$(curl -s -X POST "http://localhost:8090/realms/bank/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=rdmmesh-ui \
    -d username=dev-author -d password=dev -d scope=openid \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')
HDR="Authorization: Bearer $TOKEN"

# Domain + CodeSet с IFRS9 stage-схемой (E3 endpoints)
DOM_ID=$(curl -s -X POST -H "$HDR_ADMIN" -H 'Content-Type: application/json' -d '{...}' .../domains | jq -r .id)
CS_ID=$(curl -s -X POST -H "$HDR" -H 'Content-Type: application/json' -d '{
  "name":"ifrs9_stages_e4","display_name":"IFRS9 stages","hierarchy_mode":"NONE",
  "initial_schema":{"type":"object","required":["stage"],"properties":{"stage":{"type":"string","enum":["1","2","3"]}},"additionalProperties":false}
}' .../codesets/by-domain/$DOM_ID | jq -r .id)

# E4 path: draft → items → diff
V1=$(curl -s -X POST -H "$HDR" -H 'Content-Type: application/json' -d '{}' .../versions/by-codeset/$CS_ID | jq -r .id)
# → 201, version=0.1.0, status=DRAFT, schema_version=1, item_count=0

# Add valid item — 201
curl -X POST -H "$HDR" -H 'Content-Type: application/json' \
    -d '{"key_parts":["S2"],"label_ru":"Stage 2","label_en":"Stage 2","attributes":{"stage":"2"}}' \
    .../versions/$V1/items

# Add invalid (stage=4) — 422 + список ошибок схемы
curl -X POST ... -d '{"key_parts":["BAD"],"attributes":{"stage":"4"}}' .../versions/$V1/items

# Bulk JSON upsert — 200 APPLIED rowsAdded=2
curl -X POST -H "$HDR" -H 'Content-Type: application/json' \
    -d '[{"key_parts":["S1"],...},{"key_parts":["S3"],...}]' .../versions/$V1/items/bulk

# List
curl ... ".../versions/$V1/items?page=0&size=10"   # → total=3, keys=[S1,S2,S3]

# Get by composite key (base64url)
KEY=$(python3 -c 'import base64,json; print(base64.urlsafe_b64encode(json.dumps(["S2"]).encode()).decode().rstrip("="))')
curl ... .../versions/$V1/items/$KEY                  # → row_version=0

# PATCH with correct expected_row_version=0 → 200 row_version→1
curl -X PATCH -H "$HDR" -H 'Content-Type: application/json' \
    -d '{"expected_row_version":0,"label_ru":"Stage 2 (updated)"}' .../versions/$V1/items/$ITEM_ID

# PATCH same again with stale 0 → 409 Conflict
curl -X PATCH ... -d '{"expected_row_version":0,...}' .../versions/$V1/items/$ITEM_ID

# Bulk CSV
printf 'key_parts,label_ru,label_en,attributes\nZ1,Зет 1,Z 1,"{""stage"":""1""}"\nZ2,Зет 2,Z 2,"{""stage"":""2""}"\n' \
  | curl -X POST -H "$HDR" -H 'Content-Type: text/csv' --data-binary @- .../versions/$V1/items/bulk-csv
# → 200 APPLIED rowsAdded=2

# Битая CSV-строка — 422 (не 500)
echo 'key_parts,label_ru\n"abc broken row...' | curl -X POST -H "$HDR" -H 'Content-Type: text/csv' --data-binary @- .../bulk-csv
# → 422 REJECTED + errors[0].field=csv

# Diff между двумя версиями
V2=$(curl -X POST ... -d '{"version":"0.2.0"}' .../versions/by-codeset/$CS_ID | jq -r .id)
# заполнить v2 (S1 без изменений, S2 с изменённым label, NEW добавлен — S3/Z1/Z2 удалены)
curl -X POST ... .../versions/$V2/items/bulk -d '[{S1},{S2 modified},{NEW}]'
curl ".../versions/$V2/diff?from=$V1"
# → summary={added:1, changed:1, removed:4, moved:0}
# → entries[].changed_fields для CHANGED — точечно ["label_en","label_ru"]
```

`make verify` — зелёный, **50 тестов** = 31 unit'а в authoring (SemVer/KeyEncoding/AttributesValidator/DiffCalculator/CsvBulkParser) + 8 JwtValidator из E2 + 11 ArchUnit (8 internal-isolation + cross-module + audit-stub + distribution-stub).

---

## 3. Что осталось доделать в E4 — мягкие follow-up'ы

Ничего не блокирует E5. Список того, что **нужно вернуться** позже:

1. **Closure rebuild на каждый write — будет узкое место для крупных draft'ов.** Сейчас INSERT/UPDATE/DELETE CodeItem'а запускает `WITH RECURSIVE` через всё дерево. Для draft'ов с >1000 items это начнёт тормозить. Когда дойдёт до E13 (Bitemporal & Hierarchy) — переписать на инкрементальную поддержку closure'а через триггеры либо batch'и. Для пилота Risk/IFRS9 (десятки items) — приемлемо.
2. **`code_item.label_ru`/`label_en` могут быть NULL** в БД, но JSON Schema CodeItem'а помечает `label` как required. Сериализатор отдаёт `null` поля — клиенту это безопасно, но строгая re-валидация против spec-схемы тут падает. Не критично (ни один потребитель сейчас не валидирует CodeItem против rdmmesh-spec/schema/entity/code-item.json — они все используют активную CodeSetSchema). Если надо — добавится `NOT NULL CHECK label_ru IS NOT NULL OR label_en IS NOT NULL` миграцией V022.
3. **`POST /versions/by-codeset/{id}` пока разрешает несколько одновременных DRAFT'ов**, если у них разные semver. Это нормально до E5 (там state machine введёт правило «не больше одного DRAFT на CodeSet»). Сейчас явно не запрещаем — упало бы на UNIQUE(codeset_id, version) только при конфликте semver.
4. **Authoring integration test через testcontainers** (мягкий debt из E3). Зависимости уже подключены в `rdmmesh-app/pom.xml` (testcontainers/postgresql + junit-jupiter). Удобный момент — на старте E5, когда понадобится поднять реальную БД для проверки workflow-инвариантов на нескольких таблицах.
5. **POJO `bank.rdmmesh.spec.entity.CodeItem` сейчас не используется** в authoring resource'ах — мы возвращаем `CodeItemDto`. POJO остаётся только source-of-truth для контракта (через JSON Schema). Когда E11 (UI) сгенерирует TS-типы — это станет полезно. Pojo не удалять.
6. **Bulk endpoint'ы НЕ покрыты optimistic-lock'ом.** Bulk upsert делает blind UPDATE без `expected_row_version` — это намеренно, потому что bulk import — это всегда write со стороны автора по новой задумке, а не concurrent edit. Если когда-то понадобится "force-merge with conflict report" — это будет отдельный endpoint.

---

## 4. Технический долг и решения, повлиявшие на следующие эпики

| Что | Где | Когда снять |
|---|---|---|
| Flyway `outOfOrder=true` | `RdmmeshApplication.runFlyway` | **Не снимать** — это правильное поведение для модульной нумерации миграций |
| ArchUnit `<module>_internal_only_used_by_<module>` для пустых модулей с `allowEmptyShould(true)` | `ModuleIsolationTest` | По мере наполнения: workflow → E5, publishing → E6, distribution → E8, audit → E10 |
| `applyChangeEvent` остаётся `UnsupportedOperationException` в `PostgresOwnershipPort` | `rdmmesh-ownership` | E7 (webhook receiver) |
| `at-most-one-DRAFT` для CodeSet ещё не enforced в authoring | `AuthoringService.createDraft` + `WorkflowPort` | E5 |
| Composite key URL-encoding — `base64url(JSON)` | `KeyEncoding` | оставить — паттерн для всех URL'ов с composite key |

---

## 5. Указатели на следующие эпики

> Конкретное содержание — в SPEC §5.1.

### E5. Workflow (следующий)

- **Где:** `rdmmesh-workflow`. ~200 строк — enum state machine + JDBI Repository (SPEC §3.1).
- **Что реализовать:**
  - `WorkflowPort.transition(versionId, action, comment, byUser)` — переходы DRAFT → IN_REVIEW → STEWARD_APPROVED → OWNER_APPROVED → PUBLISHED → DEPRECATED + REJECTED.
  - **Self-approval prevention:** `created_by ≠ reviewed_by ≠ approved_by` — реализуется через `SelfApprovalException`. ID берутся из `RdmmeshPrincipal.omUserId()`.
  - **No-bypass:** даже у `RDM_ADMIN` нет permission `workflow.skip` (SPEC §3.2 #7). Hotfix workflow — только через явный шаблон (V1+).
  - **At-most-one-DRAFT** (закрывает follow-up §3 п.3 этого handoff'а): при попытке создать DRAFT, когда уже есть DRAFT — пресекать в `WorkflowPort` либо в `AuthoringService` (через port-вызов).
- **REST:** `POST /versions/{id}/transitions` (SPEC §3.5) с body `{"to": "IN_REVIEW", "comment": "..."}`.
- **Approval-tasks:** материализованный view «My Tasks» — у нас уже есть таблица `workflow.approval_task` (миграция V030 из E1). DAO + Resource.
- **Asset-level role check:** при `steward_approve` нужно проверить, что `byUser` имеет роль STEWARD на этом CodeSet — через `OwnershipPort.rolesOf(codesetId, omUserId)`.
- **Снять `allowEmptyShould` с `workflow_internal_only_used_by_workflow`** сразу после первого класса в `internal..`.

### E6. Publishing

- **Где:** `rdmmesh-publishing`. После OWNER_APPROVED — автоматический snapshot.
- **content_hash:** SHA-256 от **детерминированной** JSON-сериализации snapshot'а (ключи отсортированы, items по `key_parts`). Алгоритм нужно задокументировать в коде.
- **HMAC ключ:** через новый `SecretsPort` либо `IdentityPort` с подключением к Vault/SOPS. **В БД секрет не хранить.**
- **Иммутабельность:** при PUBLISHED версии — никаких UPDATE/DELETE на CodeItem'ах. Authoring DAO уже зашивает это в WHERE-clause (`v.status = 'DRAFT'`). Publishing должен дополнительно ставить REVOKE на роль или enforce'ить через триггер.

### E7. Ownership webhook

- **Где:** `rdmmesh-ownership`. POST `/webhooks/om/ownership`. Idempotency через `source_event_id`.
- **HMAC проверка:** заголовок `X-OM-Signature` (SPEC §2.4); поделиться секретом с OM Event Subscription.
- **Реализовать `applyChangeEvent`** — текущий stub (`UnsupportedOperationException`) должен превратиться в реальный UPSERT по delta.

### E8 / E9 / E10 / E11 / E12 / E13 / E14 — см. SPEC §5.1.

---

## 6. Открытые вопросы (всё ещё актуальны для команды банка)

Без изменений с E2/E3:

1. Production-Strategy для Flyway — подтверждено: `autoMigrate=false` в prod, миграции — отдельный шаг. Зафиксировано в `config-prod.yml`. Согласовать с эксплуатацией перед первым релизом.
2. Реальные prod-параметры Keycloak (issuer/jwks/audience/client_secret для backend).
3. OM API base URL и bot-токен.
4. HMAC secret rotation policy (для E6).

---

## 7. Версия документа

- **0.1** — 2026-05-04. Создан после завершения E4 (Authoring). Автор: Claude Opus 4.7.
