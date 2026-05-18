# Handoff — Эпик E8 (Distribution)

> **Аудитория документа.** AI-агенты и инженеры, подключающиеся к проекту после E8. Документ самодостаточен — переписки и контекста предыдущей сессии у вас нет, всё что нужно — здесь, в [`SPEC.md`](../../SPEC.md), [`E1-foundation.md`](E1-foundation.md), [`E2-identity.md`](E2-identity.md), [`E3-catalog.md`](E3-catalog.md), [`E4-authoring.md`](E4-authoring.md), [`E5-workflow.md`](E5-workflow.md), [`E6-publishing.md`](E6-publishing.md) и [`E7-ownership.md`](E7-ownership.md).
>
> **Дата handoff'а.** 2026-05-06.
> **Состояние:** E8 закрыт по содержанию SPEC §5.1. `make verify` зелёный — **101 тест** (17 distribution + 22 StateMachineTest + 31 authoring + 8 JwtValidator + 12 ownership + 11 ArchUnit). End-to-end smoke прошёл по 15 шагам — items с lang/version/as_of/knowledge_as_of, lookup, export json/csv, parquet→501, негативы (404 unknown, 401 без JWT, 400 на bad version).
> **Следующий эпик:** E9 (Outbound webhooks). Указатели — в §5.

---

## 0. TL;DR за 30 секунд

- Реализован модуль `rdmmesh-distribution` E8-часть:
  - **GET `/api/v1/rdm/{domain}/{codeset}/items`** — paginated read, фильтры `version`, `as_of`, `knowledge_as_of`, `lang`, `page`, `size`.
  - **GET `/api/v1/rdm/{domain}/{codeset}/lookup/{key}`** — одиночный item по composite key (base64url(JSON-array)).
  - **GET `/api/v1/rdm/{domain}/{codeset}/export?format=csv|json`** — bulk-export всех items версии. parquet/xlsx → 501 NOT_IMPLEMENTED (V1+).
- **Bitemporal resolver** (SPEC §2.3): `knowledge_as_of` (system time) → `findVersionKnownAt` через GiST `tstzrange(system_from, system_to)`; `as_of` (effective time) → фильтр items по `daterange(effective_from, effective_to)`. Оба индекса заведены ещё в V020.
- Все эндпоинты под `@Auth RdmmeshPrincipal` — Bearer JWT обязателен (любая base-роль). Без `@RolesAllowed` — distribution открыт для любого authenticated consumer'а.
- ArchUnit: оба правила сняты с `allowEmpty(true)`:
  - `distribution_internal_only_used_by_distribution` → strict.
  - `distribution_does_no_db_writes` → strict (запрет depend on `@SqlUpdate`/`@SqlBatch`).
- Никаких новых миграций; distribution читает существующие `catalog.code_set`, `catalog.domain`, `authoring.code_set_version`, `authoring.code_item`.

---

## 1. Что сделано

### 1.1. Структура модуля

```
rdmmesh-distribution/src/main/java/bank/rdmmesh/distribution/
├── DistributionModule.java                    ← composition factory: build(jdbi, json) → resource
├── internal/
│   ├── KeyEncoding.java                       ← decode base64url(JSON) (read-only — encoder не нужен)
│   ├── VersionResolver.java                   ← parse version="published"|<semver>, parse as_of/knowledge_as_of
│   ├── dao/
│   │   └── DistributionDao.java               ← @SqlQuery only: code_set + version + items + lookup
│   └── service/
│       └── DistributionService.java           ← оркестратор: resolve → fetch → toItem
└── resource/
    └── RdmDistributionResource.java           ← @Path("/rdm/{domain}/{codeset}")

src/test/java/.../internal/
├── VersionResolverTest.java                   ← 11 unit-тестов (default, semver, garbage rejection, дата/instant)
└── KeyEncodingTest.java                       ← 6 unit-тестов (single/composite, garbage, non-array)
```

### 1.2. Алгоритм resolve версии (SPEC §2.3 + §3.5)

Реализован в `DistributionService.resolveVersion`:

```
if knowledge_as_of:                      ← bitemporal system time
    SELECT FROM code_set_version
     WHERE codeset_id = ?
       AND status IN ('PUBLISHED','DEPRECATED')
       AND tstzrange(system_from, system_to, '[)') @> :ts
     ORDER BY published_at DESC LIMIT 1
elif version == "<semver>":              ← явная версия (PUBLISHED|DEPRECATED)
    SELECT FROM code_set_version
     WHERE codeset_id = ? AND version = ?
       AND status IN ('PUBLISHED','DEPRECATED')
else (default "published"):              ← latest released
    SELECT FROM code_set_version
     WHERE codeset_id = ? AND status = 'PUBLISHED'
     ORDER BY published_at DESC LIMIT 1
```

`as_of` (effective time, SPEC §2.3) — отдельный фильтр items, не версии:

```
WHERE daterange(coalesce(effective_from, '-infinity'), coalesce(effective_to, 'infinity'), '[)') @> :date
```

Для пилота `effective_from`/`effective_to` пустые → daterange `[-infinity, infinity)` → запись «действует всегда».

DRAFT/IN_REVIEW/STEWARD_APPROVED/OWNER_APPROVED версии **никогда** не возвращаются — distribution отдаёт только released данные.

### 1.3. Bitemporal сценарии (SPEC §2.3)

| Запрос | Что возвращается |
|---|---|
| `?` (без параметров) | latest PUBLISHED, все items этой версии |
| `?as_of=2026-06-01` | latest PUBLISHED, items действующие на 2026-06-01 (effective_from ≤ 2026-06-01 < effective_to) |
| `?knowledge_as_of=2026-04-01T00:00:00Z` | версия, известная системе на эту дату |
| `?as_of=2025-12-31&knowledge_as_of=2026-01-15T00:00:00Z` | версия, известная на 2026-01-15, items действующие на 2025-12-31 (классический regulatory query из §2.3) |
| `?version=0.1.0` | конкретная версия независимо от status'а (PUBLISHED или DEPRECATED) |

Если на запрашиваемую дату ни одной known версии нет — 404 «На <date> система не знала ни одной published-версии».

### 1.4. Контракт ItemsPage

```json
{
  "domain": "risk_e8",
  "codeset": "e8_codeset",
  "version": "0.1.0",
  "versionId": "<uuid>",
  "status": "PUBLISHED",
  "contentHash": "<sha256-hex>",
  "publishedAt": "2026-05-06T07:02:03.388901Z",
  "page": 1,
  "size": 1000,
  "total": 3,
  "items": [
    {
      "keyParts": ["S1"],
      "parentKey": null,
      "label": "Стадия 1",        // выбран по lang (ru/en) с fallback
      "description": null,
      "attributes": {"stage": "1"},
      "orderIndex": 0,
      "status": "ACTIVE",
      "effectiveFrom": "2026-01-01",
      "effectiveTo": null
    }
  ]
}
```

Lang fallback в `DistributionService.pick`:
- `lang=ru` (default) → `label_ru ?? label_en`.
- `lang=en` → `label_en ?? label_ru`.

`contentHash` отдаётся клиенту — он может сравнивать его с тем, что прислал OM webhook'ом, или использовать как ETag для собственного кэша.

### 1.5. Lookup

```
GET /api/v1/rdm/{domain}/{codeset}/lookup/{key}
```

`{key}` — base64url(JSON-array без padding'а), формат тот же, что у authoring (`KeyEncoding`):

```python
import base64, json
key = base64.urlsafe_b64encode(json.dumps(["RETAIL","BB","12M"]).encode()).decode().rstrip("=")
# → "WyJSRVRBSUwiLCJCQiIsIjEyTSJd"
```

При несуществующем key — 404 «item не найден: {token}». При невалидном base64 — 400.

### 1.6. Export

| Format | Поддерживается на E8 | Замечания |
|---|---|---|
| `json` | ✅ | Тот же shape что items, но без пагинации (все items одним массивом) + content_hash в корне. |
| `csv`  | ✅ | Колонки: `key_parts, parent_key, label, description, attributes, order_index, status, effective_from, effective_to`. `key_parts`/`parent_key`/`attributes` — JSON-строки (массив/объект как строка). Используется обычный `ObjectMapper` (не `CsvMapper`) — это закрыло баг с trailing newline в CsvMapper.writeValueAsString. |
| `parquet` | ❌ → 501 | V1+ feature. Требует `org.apache.parquet:parquet-avro` (heavy dep). |
| `xlsx` | ❌ → 501 | V1+ feature. Требует `org.apache.poi:poi-ooxml`. |

CSV-экспорт **не разворачивает attributes по колонкам**. Это требует resolve'а активной CodeSetSchema на стороне сервиса либо клиента; для пилота Risk-engine достаточно JSON-строки в одной колонке. Расширение — V1+ (см. §3 debt).

Streaming в `RdmDistributionResource.csvResponse`: response — `StreamingOutput`, тело пишется напрямую в `OutputStream`. Память-на-item ограничена; CSV для 100k записей укладывается в десятки МБ heap.

### 1.7. Wiring

`RdmmeshApplication.run(...)`:

```java
environment.jersey().register(
        DistributionModule.buildResource(jdbi, environment.getObjectMapper()));
```

Никаких portов, никаких composition-factory расширений — distribution самодостаточен в чтении из существующих таблиц.

### 1.8. ArchUnit gates

`ModuleIsolationTest`:

```java
@ArchTest
static final ArchRule distribution_internal_only_used_by_distribution =
        internalSliceUsedOnlyByStrict("distribution");      // strict (был allowEmpty)

@ArchTest
static final ArchRule distribution_does_no_db_writes =
        noClasses().that().resideInAPackage("bank.rdmmesh.distribution..")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("org.jdbi.v3.sqlobject.statement.SqlUpdate")
                .orShould().dependOnClassesThat()
                .haveFullyQualifiedName("org.jdbi.v3.sqlobject.statement.SqlBatch");
                                                            // strict (был allowEmpty)
```

Если кто-то добавит `@SqlUpdate` в DistributionDao — build падёт сразу. Это явный compile-time gate read-only-инварианта SPEC §3.3.

---

## 2. Контракт (REST)

```
GET /api/v1/rdm/{domain}/{codeset}/items
    ?version=published|<semver>     (default: "published")
    ?as_of=YYYY-MM-DD               (effective time)
    ?knowledge_as_of=ISO-8601       (system time, bitemporal)
    ?lang=ru|en                     (default: ru)
    ?page=1&size=1000               (max size: 10000)
  Auth: Bearer JWT (любая base-role)
  200 → ItemsPage (см. §1.4)
  400 → bad request (invalid version | size out of range | malformed date/instant)
  401 → no Bearer JWT
  404 → unknown domain/codeset | no published version | no version known at <date>

GET /api/v1/rdm/{domain}/{codeset}/lookup/{key}
    + те же query-params для resolve версии
  Auth: Bearer JWT
  200 → ItemDto
  400 → invalid key token
  404 → item не найден

GET /api/v1/rdm/{domain}/{codeset}/export
    ?format=csv|json                (default: json)
    + те же query-params для resolve версии
  Auth: Bearer JWT
  200 → CSV или ExportResult JSON
  400 → unknown format
  501 → parquet|xlsx (V1+)
```

---

## 3. Smoke (то, что прошло на 2026-05-06)

```bash
make up
# Подготовка: domain + codeset + 3 items (S1/S2/S3, effective_from=2026-01-01)
# + полный 4-eyes flow → auto-publish 0.1.0 (E5/E6).

TOK=$(KC_USER=dev-author make kc-token)
H="Authorization: Bearer $TOK"
BASE=http://localhost:8080/api/v1/rdm/risk_e8/e8_codeset

# 1. items (default published, lang=ru)
curl -H "$H" "$BASE/items"
# → 200, version=0.1.0, status=PUBLISHED, total=3, label="Стадия 1/2/3"

# 2. lang=en — labels по-английски (Stage 1/2/3)
curl -H "$H" "$BASE/items?lang=en"

# 3. version=0.1.0 явно
curl -H "$H" "$BASE/items?version=0.1.0"
# → status=PUBLISHED, total=3

# 4. as_of=2025-12-31 (до effective_from items'ов) → total=0
curl -H "$H" "$BASE/items?as_of=2025-12-31"

# 5. as_of=2026-06-01 → total=3
curl -H "$H" "$BASE/items?as_of=2026-06-01"

# 6. lookup S2
KEY=$(python3 -c 'import base64,json; print(base64.urlsafe_b64encode(json.dumps(["S2"]).encode()).decode().rstrip("="))')
curl -H "$H" "$BASE/lookup/$KEY"
# → ItemDto S2

# 7. lookup unknown — 404
KEYBAD=$(python3 -c 'import base64,json; print(base64.urlsafe_b64encode(json.dumps(["NOPE"]).encode()).decode().rstrip("="))')
curl -H "$H" "$BASE/lookup/$KEYBAD"          # 404

# 8. неизвестный domain — 404
curl -H "$H" http://localhost:8080/api/v1/rdm/no_such/codeset/items   # 404

# 9. без JWT — 401
curl "$BASE/items"                           # 401

# 10. некорректная version — 400
curl -H "$H" "$BASE/items?version=DROP%20TABLE"   # 400

# 11. export json
curl -H "$H" "$BASE/export?format=json"
# → ExportResult { domain, codeset, version, contentHash, items[3] }

# 12. export csv
curl -H "$H" "$BASE/export?format=csv"
# → text/csv с header + 3 строками; key_parts/attributes как JSON-строки

# 13. export parquet — 501
curl -H "$H" "$BASE/export?format=parquet"   # 501

# 14. knowledge_as_of=2026-01-01T00:00:00Z (до publish'а) → 404
curl -H "$H" "$BASE/items?knowledge_as_of=2026-01-01T00:00:00Z"

# 15. knowledge_as_of=now() → текущая published
curl -H "$H" "$BASE/items?knowledge_as_of=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
# → version=0.1.0, total=3
```

`make verify` — зелёный, **101 тест**:
- 17 distribution (11 VersionResolverTest + 6 KeyEncodingTest)
- 22 StateMachineTest (E5/E6)
- 31 authoring (SemVer/KeyEncoding/AttributesValidator/DiffCalculator/CsvBulkParser)
- 8 JwtValidatorTest (E2)
- 12 ownership (6 HmacVerifierTest + 6 FqnParserTest, E7)
- 11 ArchUnit (cross-module + 8 strict-internal + audit-stub + distribution-strict)

---

## 4. Технический долг и решения, повлиявшие на следующие эпики

| Что | Где | Когда снять / следующий шаг |
|---|---|---|
| KeyEncoding продублирован в distribution и authoring | `bank.rdmmesh.distribution.internal.KeyEncoding` + `bank.rdmmesh.authoring.internal.KeyEncoding` | Можно вынести в `rdmmesh-api/util/`, но это рефакторинг ради DRY. На пилоте 30 строк дубликата приемлемы; SPEC не требует. |
| Export CSV не разворачивает attributes по колонкам | `RdmDistributionResource.toCsvRow` | V1+: получить активную CodeSetSchema через `CatalogReadPort.currentSchema(codesetId)`, развернуть keys → отдельные колонки. Сейчас attributes — одна колонка с JSON-строкой. |
| parquet/xlsx → 501 | `RdmDistributionResource.export` | V1+. Parquet требует `parquet-avro` (~30 МБ deps), XLSX — `poi-ooxml`. Для пилота не critical. |
| HTTP cache headers (ETag, Cache-Control) не выставляются | `RdmDistributionResource` | content_hash уже отдаётся в payload — можно использовать как ETag. Добавить через `ResponseBuilder.tag(EntityTag)` и conditional GET (If-None-Match). Скорее V1+. |
| Pagination — only OFFSET/LIMIT | `DistributionDao.findItemsPage` | Для крупных версий (миллионы) OFFSET становится дорогим. Cursor-based pagination через `WHERE key_parts > :last_key` — V1+ оптимизация. |
| Streaming для export — full materialisation | `DistributionService.fetchAllItems` | Сейчас всё в `List<ItemDto>` в heap. Для пилота десятки тысяч ОК. При росте — JDBI streaming через `ResultIterable.stream()` + `StreamingOutput`. |
| DEPRECATED-версии возвращаются по semver, но НЕ через `version=published` | `DistributionService.resolveVersion` | Это by design (SPEC §2.2 «DEPRECATED — не удаляются никогда»; consumer явно указывает version). Документировать в API-спеке для consumer'ов. |
| Distribution не публикует никаких events | `DistributionService` | Read-only — нет смысла. Audit (E10) подписывается на write-стороны. |
| `effective_from`/`effective_to` пилотные records не используют (NULL) | `code_item` | Когда Risk-engine начнёт грузить реальные IFRS9-данные, `effective_from` пойдёт от регуляторных дат. Тогда `as_of`-фильтр станет полезен; до того — он работает, но возвращает то же что без него. |

---

## 5. Указатели на следующие эпики

> Конкретное содержание — в SPEC §5.1.

### E9. Outbound webhooks (следующий)

- **Где:** `rdmmesh-publishing` (расширение существующего модуля) поверх `webhook_subscription` + `webhook_outbox` (миграция V040 их уже создала).
- **Что реализовать:**
  - REST `GET/POST/DELETE /api/v1/subscriptions` для CRUD'а consumer-webhook'ов.
  - `OutboundPort.enqueueVersionPublished(VersionPublishedEvent)` — реализация: INSERT в `webhook_outbox` в той же транзакции, что publish.
  - Background worker (Dropwizard `Managed`) — дренирует outbox с retry/backoff, HMAC-подписывает payload (как E6 outbound), идемпотентен по `event_id`.
  - `PublishingService.autoPublish` после успешного publish'а зовёт `outboundPort.enqueueVersionPublished(...)`. Spec-POJO `VersionPublishedEvent` уже есть.
- **Зависимости:** E6 ✓.

### E10. Audit

- **Где:** `rdmmesh-audit`. Сейчас пуст.
- Подписаться на `EventBus.subscribe(DomainEvent.class, ...)` глобально, INSERT в `audit.audit_log` через JDBI.
- Ownership webhook events (E7) сейчас НЕ публикуются в EventBus — handoff E7 §4 это упомянул. Решить: либо публиковать `OwnershipChangedDomainEvent`, либо в E10 audit подписываться на JDBI write-listener (sketchier).
- **Снять `allowEmpty`** с `audit_only_depends_on_api_or_spec` и `audit_internal_only_used_by_audit` — последние два правила всё ещё с `allowEmptyShould(true)`.

### E11. UI

- **Где:** `rdmmesh-ui` (placeholder). React + TS + AntD + TanStack Table + Vite.
- Экраны: Catalog tree, CodeSet view, Items grid editor, Versions list, Diff, My Tasks.
- TS-кодогенерация из JSON Schema'ы — handoff E1 §3.8 это упомянул.

### E12 / E13 / E14 — см. SPEC §5.1.

---

## 6. Открытые вопросы (актуальны для команды банка)

Без изменений с E7, плюс:

1. Production-Strategy для Flyway — подтверждено: `autoMigrate=false` в prod.
2. Реальные prod-параметры Keycloak (issuer/jwks/audience/client_secret).
3. OM API base URL и bot-токен.
4. HMAC secret rotation policy — outbound (E6) И inbound (E7).
5. Уведомления (e-mail/Slack) approver'ам — V1+.
6. RDM_ADMIN substitution policy.
7. Имена env-vars для HMAC.
8. Webhook URL OM согласован с `/api/v1/webhooks/om/ownership`?
9. Политика «expert == steward».
10. APPROVER mapping из reviewers.
11. **Distribution — нужны ли HTTP cache headers (ETag/Cache-Control) для consumer-систем?** Сейчас отдаётся свежий ответ на каждый запрос. Risk-engine на пилоте читает раз в день, так что не critical; для high-frequency consumer'ов (BI-дашборды) — желательно.
12. **Нужен ли rate-limit на distribution-эндпоинтах?** Один Risk-engine — нет; десятки систем — нужно. Через Dropwizard-Bucket или nginx upstream.

---

## 7. Версия документа

- **0.1** — 2026-05-06. Создан после реализации E8 (Distribution). Build/smoke прогнаны end-to-end. Автор: Claude Opus 4.7.
