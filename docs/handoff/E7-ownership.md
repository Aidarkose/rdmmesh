# Handoff — Эпик E7 (Ownership webhook)

> **Аудитория документа.** AI-агенты и инженеры, подключающиеся к проекту после E7. Документ самодостаточен — переписки и контекста предыдущей сессии у вас нет, всё что нужно — здесь, в [`SPEC.md`](../../SPEC.md), [`E1-foundation.md`](E1-foundation.md), [`E2-identity.md`](E2-identity.md), [`E3-catalog.md`](E3-catalog.md), [`E4-authoring.md`](E4-authoring.md), [`E5-workflow.md`](E5-workflow.md) и [`E6-publishing.md`](E6-publishing.md).
>
> **Дата handoff'а.** 2026-05-06.
> **Состояние:** E7 закрыт по содержанию SPEC §5.1. `make verify` зелёный — **84 теста** (12 новых ownership/webhook + 22 StateMachineTest + 31 authoring + 8 JwtValidator + 11 ArchUnit). End-to-end smoke прошёл по 9 шагам — domain UPSERT, HMAC mismatch, idempotency duplicate, table-event apply + delta, чужой FQN ignored, unknown CodeSet, журнал processed_om_event.
> **Следующий эпик:** E8 (Distribution). Указатели — в §5.
>
> **⚠ Forward-pointer (2026-05-19, не переписывает историю E7).** Эпик
> [`E17-approver-routing.md`](E17-approver-routing.md) добавляет в модуль
> `ownership` **отдельный** справочник `ownership.domain_role_directory`
> (`домен → роль(STEWARD|BUSINESS_OWNER) → учётка`) для адресной маршрутизации
> согласования (SPEC §2.4, BR-21/BR-22). Это **не** `rdm_asset_ownership`:
> у справочника другая семантика обновления — **полная замена `TRUNCATE+INSERT`**
> одной транзакцией (снапшот от мастера), а не дельта-UPSERT по webhook'у,
> описанному ниже. Мастер — OpenMetadata; на старте локальный сид, позже —
> OM-генерация (ADR-009). Webhook-канал §1.4 и mapping §1.5 остаются без
> изменений. `BUSINESS_OWNER` = владелец домена (тот же субъект, что приходит
> как OM-owner для `entity_type=domain`). См. E17 §2/§5/§7.

---

## 0. TL;DR за 30 секунд

- Реализован модуль `rdmmesh-ownership` E7-часть:
  - **POST `/api/v1/webhooks/om/ownership`** — принимает `OwnershipChangedEvent` (rdmmesh-spec/schema/events/ownership-changed-event.json). Auth — HMAC-SHA-256 over raw body, header `X-OM-Signature: sha256=<hex>` (GitHub-style). Без JWT; HMAC сам по себе аутентифицирует OM.
  - Идемпотентность в двух слоях: журнал `ownership.processed_om_event` (V061) отсекает дубликаты по `event_id` ДО изменения catalog/ownership; UNIQUE-индексы на `rdm_asset_ownership` и `catalog.domain` — фоновый второй защитный слой.
  - Для `entity_type=domain` — UPSERT в `catalog.domain` через новый `CatalogMirrorPort`. Op фиксируется как CREATED/UPDATED/RESURRECTED/UNCHANGED.
  - Для `entity_type=table` — парсинг FQN `rdmmesh.<domain>.<codeset>`, lookup CodeSet'а, расчёт delta «desired - current» по `owners`/`experts`/`reviewers`, applyChangeEvent → UPSERT/DELETE в `rdm_asset_ownership`.
  - Mapping: `owners → OWNER`, `experts → EXPERT (+ STEWARD по политике "expert == steward", SPEC §2.4)`, `reviewers → APPROVER`.
- Расширен `DomainDao`: `upsertByOmId`, `softDeleteByOmId`, новый `deleted_at` в схеме (V011).
- `PostgresOwnershipPort.applyChangeEvent` больше не бросает `UnsupportedOperationException` — реализован атомарный UPSERT/DELETE по delta.
- Новый порт **`CatalogMirrorPort`** в `rdmmesh-api` (sync-контракт catalog'а: domain UPSERT/soft-delete + lookup CodeSet'а по FQN). Реализация — `CatalogMirrorAdapter` в catalog.
- ArchUnit: `ownership_internal_only_used_by_ownership` переведено на strict (`internalSliceUsedOnlyByStrict`), как только в `rdmmesh-ownership/.../internal/webhook` появились классы.

---

## 1. Что сделано

### 1.1. Миграции

| Файл | Что |
|---|---|
| `bootstrap/sql/migrations/catalog/V011__catalog_domain_softdelete.sql` | `ALTER TABLE catalog.domain ADD COLUMN deleted_at timestamptz` + partial index `WHERE deleted_at IS NOT NULL`. Soft-delete нужен потому что downstream-CodeSet'ы держат FK на `domain.id` (ON DELETE RESTRICT). |
| `bootstrap/sql/migrations/ownership/V061__ownership_processed_events.sql` | `ownership.processed_om_event (event_id PK, entity_type, fqn, occurred_at, received_at, payload_sha256)`. Журнал обработанных webhook'ов для HTTP-level idempotency. |

После сборки нового образа (`docker compose build rdmmesh-service`) и `compose down -v && up`: применяются 11 миграций, V001..V070.

### 1.2. Новые порты в `rdmmesh-api`

```
rdmmesh-api/src/main/java/bank/rdmmesh/api/port/
  └── CatalogMirrorPort.java
```

Контракт:
```java
DomainMirrorResult upsertDomainFromOm(DomainMirror mirror);   // UPSERT по om_domain_id
boolean softDeleteDomainByOmId(UUID omDomainId);              // deleted_at = now()
Optional<UUID> findCodeSetIdByFqn(String domainName, String codesetName);

record DomainMirror(UUID omDomainId, String name, String displayName, ..., String[] tags);
record DomainMirrorResult(UUID id, UUID omDomainId, MirrorOp op);
enum MirrorOp { CREATED, UPDATED, RESURRECTED, UNCHANGED }
```

Отдельный порт от `CatalogReadPort`: тот — read для соседних bounded contexts (authoring/publishing), у этого специфичная sync-семантика (input — поток из OM webhook'а).

### 1.3. Структура модуля `rdmmesh-ownership`

```
rdmmesh-ownership/src/main/java/bank/rdmmesh/ownership/
├── OwnershipModule.java                       ← +buildWebhookResource(jdbi, mirror, ownership, key, json)
├── internal/
│   ├── PostgresOwnershipPort.java             ← applyChangeEvent реализован; +computeDelta(...)
│   ├── dao/
│   │   ├── AssetOwnershipDao.java             ← без изменений
│   │   └── ProcessedEventDao.java             ← новый: exists() + recordIfAbsent()
│   └── webhook/
│       ├── HmacVerifier.java                  ← sha256= hex check + sign() helper
│       ├── FqnParser.java                     ← rdmmesh.<domain>.<codeset>
│       └── OwnershipWebhookService.java       ← оркестратор: dedup → apply
└── resource/
    └── OwnershipWebhookResource.java          ← POST /webhooks/om/ownership
```

Тесты:
```
rdmmesh-ownership/src/test/java/bank/rdmmesh/ownership/internal/webhook/
├── HmacVerifierTest.java                      ← 6 unit-тестов (signing roundtrip, tampered body, wrong key, malformed header, truncated)
└── FqnParserTest.java                         ← 6 unit-тестов (3-segment / wrong prefix / 2-/4-segment / empty / empty segments)
```

### 1.4. Алгоритм обработки webhook'а

```
HTTP POST /api/v1/webhooks/om/ownership
  X-OM-Signature: sha256=<hex>
  body: OwnershipChangedEvent (JSON)

1. HMAC-проверка X-OM-Signature по raw body. Mismatch → 401.
2. Парсинг JSON. Невалидный → 400.
3. Required fields: event_id, entity_type. Без них → 400.
4. ProcessedEventDao.recordIfAbsent(event_id, entity_type, fqn, occurred_at, sha256(body)):
   - 0 affected → дубликат, 200 { outcome: DUPLICATE }.
   - 1 affected → продолжаем.
5. Switch by entity_type:
   ─ "domain": entity_id (UUID) обязателен. CatalogMirrorPort.upsertDomainFromOm(...).
                Op возвращается через MirrorOp enum, попадает в response note.
   ─ "table": FqnParser.parseTable(fqn).
              — Не наш FQN → 200 { outcome: IGNORED }.
              — CodeSet не найден → 200 { outcome: UNKNOWN_ASSET }.
              — иначе computeDelta(asset, desired_owners, desired_experts, desired_reviewers)
                 + ownership.applyChangeEvent(asset, "CODESET", delta, event_id).
   ─ другое: 200 { outcome: UNSUPPORTED }.
6. Response: { outcome, event_id, entity_ref?, summary?, note? }, HTTP 200/400/401.
```

**Все non-error ответы — 200.** Это сознательное решение для OM Event Subscription: ему важно «я доставил, можно не повторять». IGNORED/UNKNOWN_ASSET/UNSUPPORTED не означают «ошибка», они означают «получено, нечего делать»; пуш-ретрай OM'а на 5xx был бы паразитной нагрузкой.

### 1.5. Mapping ролей (SPEC §2.4)

| OM-коллекция | RDM-роль |
|---|---|
| `owners`    | `OWNER` |
| `experts`   | `EXPERT` + `STEWARD` (политика «expert == steward») |
| `reviewers` | `APPROVER` |

**Политика «expert == steward»** в `PostgresOwnershipPort.EXPERT_ACTS_AS_STEWARD`. SPEC §2.4 явно даёт два варианта:

> для steward подобной семантики в OM нет — steward = expert или отдельная политика

Я выбрал «steward = expert»: каждый expert одновременно записывается в `rdm_asset_ownership` как STEWARD. Это позволяет `/tasks/my` показывать ревью-задачи реальным OM-expert'ам без отдельного UI назначения steward'ов в RDM.

При желании отключить — поменять `EXPERT_ACTS_AS_STEWARD = false` (это константа, не runtime-переменная — в проде это решение должно быть зафиксировано один раз).

### 1.6. Идемпотентность и delta

Webhook идемпотентен по двум причинам:

1. **HTTP-level** через `ownership.processed_om_event.event_id` PRIMARY KEY: дубликаты ChangeEvent.id отсекаются ДО изменения catalog/ownership. Возвращается `{outcome: DUPLICATE}`.
2. **Storage-level** через UNIQUE(asset_id, asset_type, om_user_id, role): даже если HTTP-уровень пропустит дубликат (race-condition), UPSERT не создаст лишнего. Reads через `ownersOf` останутся стабильны.

**Delta computeDelta(asset, desired)**:
- `current = SELECT FROM rdm_asset_ownership WHERE asset_id = ?`
- `added   = desired - current`
- `removed = current - desired`

Operation order в applyChangeEvent: **сначала removals, потом UPSERT'ы** — это важно при «move» (user был в одной коллекции, стал в другой), чтобы UNIQUE-конфликта не было (хотя UNIQUE — по (asset, user, role), и move через коллекции разные роли создаёт; защита всё равно полезна для self-moves через тот же role).

### 1.7. HMAC-валидация

`HmacVerifier`:
- Алгоритм: HMAC-SHA-256 over raw body, key = `SigningKeyPort.currentHmacKey()`.
- Header: `X-OM-Signature: sha256=<hex>`. Префикс `sha256=` опционален (для совместимости с разными настройками OM Event Subscription).
- Проверка подписи через `MessageDigest.isEqual(byte[], byte[])` — constant-time.
- Body берётся как `byte[]` (не `String`), чтобы HMAC посчитался ровно по тем байтам, что прислал OM (без charset-конверсий).

Ключ — отдельный от publishing'а (E6 использует `RDM_HMAC_KEY` для подписи snapshot'ов; E7 использует `RDM_OM_WEBHOOK_HMAC_KEY` для inbound webhook'а). Композируются через тот же `EnvSigningKeyAdapter.fromEnv(name, dev-fallback)`. Dev-fallback — `rdmmesh-dev-om-webhook-key-change-me-in-prod-vault` (49 байт, удовлетворяет требованию ≥32).

### 1.8. Wiring в `RdmmeshApplication`

```java
// E7 — Ownership webhook receiver.
CatalogMirrorPort catalogMirror = CatalogModule.buildMirrorPort(jdbi);
SigningKeyPort omWebhookKey = EnvSigningKeyAdapter.fromEnv(
        "RDM_OM_WEBHOOK_HMAC_KEY",
        "rdmmesh-dev-om-webhook-key-change-me-in-prod-vault");
environment.jersey().register(OwnershipModule.buildWebhookResource(
        jdbi, catalogMirror, ownershipPort, omWebhookKey, environment.getObjectMapper()));
```

### 1.9. Фактический URL webhook'а — *deviation от SPEC §2.4*

SPEC §2.4 называет endpoint `https://rdm.bank/webhooks/om/ownership`. Фактически Dropwizard rootPath = `/api/v1/*` (см. `config.yml:server.rootPath`), поэтому реальный URL — **`https://rdm.bank/api/v1/webhooks/om/ownership`**.

Альтернатива — выносить webhook на admin-port (8081) либо менять rootPath на `/*` и приписывать `/api/v1/...` каждому resource'у вручную. Обе ломают существующий контракт и не оправданы для одного endpoint'а.

OM Event Subscription конфигурируется на полный URL — изменение URL это просто смена значения в OM-конфигурации. Для prod — согласовать с командой OM.

---

## 2. Контракт (REST)

```
POST /api/v1/webhooks/om/ownership
  Content-Type: application/json
  X-OM-Signature: sha256=<hex of HMAC-SHA-256(raw_body, RDM_OM_WEBHOOK_HMAC_KEY)>
  body: OwnershipChangedEvent {
    event_id: string,            (required, UUID-like, idempotency key)
    entity_type: "domain"|"table",
    entity_id: string?,          (UUID; required для domain)
    fully_qualified_name: string,(required для domain — domain.name; для table — rdmmesh.X.Y)
    owners: string[],            (UUID-array; required, может быть [])
    experts: string[]?,
    reviewers: string[]?,
    occurred_at: string?         (ISO-8601 instant)
  }

200 →
  { outcome: "APPLIED"|"DUPLICATE"|"IGNORED"|"UNKNOWN_ASSET"|"UNSUPPORTED",
    event_id: string,
    entity_ref: "DOMAIN:<uuid>"|"CODESET:<uuid>"?,    (только для APPLIED)
    summary: string?,                                  (только для table.APPLIED)
    note: string? }

400 → { error: "..." }    (malformed payload, missing required field, bad UUID)
401 → { error: "invalid X-OM-Signature" }
```

### Outcome-таблица

| Outcome | Когда | Сторонние эффекты |
|---|---|---|
| `APPLIED` | Domain UPSERT'нут / CodeSet ownership обновлены | rows в catalog.domain или ownership.rdm_asset_ownership; запись в processed_om_event |
| `DUPLICATE` | event_id уже был обработан раньше | нет |
| `IGNORED` | FQN не начинается с `rdmmesh.` (не наша таблица) | запись в processed_om_event |
| `UNKNOWN_ASSET` | FQN валиден, но CodeSet не найден (был удалён или не успел появиться) | запись в processed_om_event |
| `UNSUPPORTED` | entity_type не в {domain, table} | запись в processed_om_event |

---

## 3. Smoke (то, что прошло на 2026-05-06)

```bash
# Используем существующий codeset cf9afe3c-87e3-4866-8e17-21aa5deece97 (создан в setup)
# либо POST .../domains + POST .../codesets/by-domain (E3 path).

KEY=rdmmesh-dev-om-webhook-key-change-me-in-prod-vault
WEBHOOK=http://localhost:8080/api/v1/webhooks/om/ownership

sign() { python3 -c "import sys,hmac,hashlib;\
  print('sha256=' + hmac.new(b'$KEY', sys.stdin.buffer.read(), hashlib.sha256).hexdigest())"; }

post() {
  local body="$1"; local sig=$(printf '%s' "$body" | sign)
  curl -sS -w '\n[HTTP %{http_code}]\n' -X POST "$WEBHOOK" \
       -H "X-OM-Signature: $sig" -H 'Content-Type: application/json' \
       --data-binary "$body"
}

# 1. domain webhook → APPLIED (CREATED)
post '{"event_id":"evt-001","entity_type":"domain","entity_id":"99999999-9999-9999-9999-999999999999","fully_qualified_name":"treasury","owners":[],"experts":[],"reviewers":[]}'
# → {"outcome":"APPLIED","event_id":"evt-001","entity_ref":"DOMAIN:<uuid>","note":"domain created"}, 200

# 2. HMAC mismatch → 401
curl -sS -w '\n[HTTP %{http_code}]\n' -X POST "$WEBHOOK" \
     -H 'X-OM-Signature: sha256=deadbeef' -H 'Content-Type: application/json' \
     --data-binary '{"event_id":"evt-001",...}'
# → {"error":"invalid X-OM-Signature"}, 401

# 3. Дубликат event_id → DUPLICATE
post '{"event_id":"evt-001",...}'   # тот же payload
# → {"outcome":"DUPLICATE","event_id":"evt-001"}, 200

# 4. Table webhook добавляет owners/experts/reviewers
post '{"event_id":"evt-002","entity_type":"table",
       "fully_qualified_name":"rdmmesh.risk_e7.e7_codeset",
       "owners":["11111111-..."],"experts":["22222222-..."],"reviewers":["33333333-..."]}'
# → {"outcome":"APPLIED",...,"summary":"owners(+1/-1) experts(+1/-0) approvers(+1/-0)"}, 200

# В rdm_asset_ownership: USER1=OWNER, USER2=EXPERT+STEWARD, USER3=APPROVER (4 строки),
# provisional dev-author удалён (был "current owner", не в desired desired list)

# 5. Delta: убираем USER2 из experts, добавляем USER1 в experts/reviewers
post '{"event_id":"evt-003","entity_type":"table","fully_qualified_name":"rdmmesh.risk_e7.e7_codeset",
       "owners":["11111111-..."],"experts":["11111111-..."],"reviewers":["11111111-...","33333333-..."]}'
# → summary "experts(+1/-1) approvers(+1/-0)"
# В таблице: USER2 удалён из EXPERT и STEWARD, USER1 в EXPERT/STEWARD/APPROVER

# 6. Чужой FQN → IGNORED
post '{"event_id":"evt-004","entity_type":"table","fully_qualified_name":"warehouse.foo.bar","owners":[],"experts":[],"reviewers":[]}'
# → {"outcome":"IGNORED",...,"note":"FQN не относится к rdmmesh: warehouse.foo.bar"}, 200

# 7. Неизвестный CodeSet → UNKNOWN_ASSET
post '{"event_id":"evt-005","entity_type":"table","fully_qualified_name":"rdmmesh.risk_e7.no_such_cs",...}'
# → {"outcome":"UNKNOWN_ASSET",...,"note":"CodeSet rdmmesh.risk_e7.no_such_cs не найден"}, 200

# 8. Журнал
psql -c "SELECT event_id, entity_type, fqn FROM ownership.processed_om_event ORDER BY received_at;"
# → 5 строк (evt-001..evt-005), evt-001 один раз даже при двух попытках (idempotent)
```

`make verify` — зелёный, **84 теста**:
- 6 HmacVerifierTest (E7) + 6 FqnParserTest (E7)
- 22 StateMachineTest (E5/E6)
- 31 authoring (SemVer/KeyEncoding/AttributesValidator/DiffCalculator/CsvBulkParser)
- 8 JwtValidatorTest (E2)
- 11 ArchUnit (cross-module + 8 strict-internal + audit-stub + distribution-stub)

---

## 4. Технический долг и решения, повлиявшие на следующие эпики

| Что | Где | Когда снять / следующий шаг |
|---|---|---|
| Webhook URL фактически `/api/v1/webhooks/om/ownership` (не `/webhooks/om/ownership` как в SPEC §2.4) | `OwnershipWebhookResource` + `config.yml:server.rootPath=/api/v1/*` | Согласовать с командой OM до prod-deploy. Изменения только в OM Event Subscription URL — на стороне rdmmesh код не трогаем. |
| ENTITY_SOFT_DELETED не различается в payload-схеме MVP | `OwnershipChangedEvent.json` + `OwnershipWebhookService.handleDomain` | OwnershipChangedEvent.json не содержит `op`-поля. Когда OM начнёт нам слать ENTITY_SOFT_DELETED — расширить spec-схему добавкой `op: "UPSERT"|"SOFT_DELETE"`, прокинуть в `CatalogMirrorPort.softDeleteDomainByOmId` уже реализован. |
| Provisional OWNER от E3 удаляется первым же table-webhook'ом | `PostgresOwnershipPort.computeDelta` | Это семантически правильно: OM становится единственным источником истины. SPEC §2.4 п.7 говорит «publish не блокируется на provisional-period» — но post-bootstrap, при пустом OM owners[], OWNER действительно исчезает; publish заблокируется отсутствием OWNER'а, не provisional-флагом. Мягкий debt — ввести "сохранять provisional пока OM не пришлёт хотя бы один реальный owner". На пилоте не критично, потому что OM назначает owner'а сразу. |
| Webhook ничего не пушит обратно в EventBus | `OwnershipWebhookService.handle` | E10 (audit) подписывается на DomainEvent.class. Webhook'ные изменения сейчас не попадают в audit. Решить с E10: либо публиковать `OwnershipChangedDomainEvent`, либо в E10 audit подписаться на JDBI write-listener. |
| HMAC-ключ rotation policy для inbound (RDM_OM_WEBHOOK_HMAC_KEY) | `EnvSigningKeyAdapter` | Тот же open question, что у outbound (E6 §6 п.4). Согласовать со стороной OM. |
| SPEC §2.4 mapping `reviewers → APPROVER`: APPROVER-роль не используется ни одним E5-state-machine-правилом | `StateMachine` | На E5 четырёх-eyes flow не использует APPROVER. SPEC говорит, что reviewers в OM — это approver, но в наш state-machine они никак не вступают. Возможно стоит оставить APPROVER только на read (для UI «Approvers ранее ревьюили эту версию»). Решить с командой compliance. |
| `ProcessedEventDao` хранит payload_sha256 — не используется на чтение | `ProcessedEventDao` + `ownership.processed_om_event` | Колонка добавлена для future use (forensic при инцидентах: если придёт событие с тем же event_id но изменённым телом, можно будет это засечь). Сейчас — write-only. |

---

## 5. Указатели на следующие эпики

> Конкретное содержание — в SPEC §5.1.

### E8. Distribution (следующий)

- **Где:** `rdmmesh-distribution`. Сейчас — пуст (`pom.xml` + `src/main/java/.gitkeep`).
- **Что реализовать:**
  - GET `/api/v1/rdm/{domain}/{codeset}/items` с `version=published|<semver>`, `as_of`, `knowledge_as_of`, `lang=ru|en`, `page`/`size`.
  - GET `/api/v1/rdm/{domain}/{codeset}/lookup/{key}`.
  - GET `/api/v1/rdm/{domain}/{codeset}/export?format=csv|json|parquet|xlsx`.
- **Read-only.** ArchUnit-rule `distribution_does_no_db_writes` сейчас с `allowEmpty=true` — снимать как только появится первый DAO. Запрещены `@SqlUpdate`/`@SqlBatch`.
- **Bitemporal.** SPEC §3.5 endpoint должен поддерживать `as_of` (effective time) и `knowledge_as_of` (system time). DAO-level: фильтр по `effective_*` + `system_*` через GiST-индексы (V020 их завёл).

### E9. Outbound webhooks

- **Где:** `rdmmesh-publishing` либо новый модуль, поверх существующего `webhook_subscription` + `webhook_outbox` (V040).
- `OutboundPort.enqueueVersionPublished(VersionPublishedEvent)` вызывается из `PublishingService.autoPublish` после publish'а.
- Background worker (Dropwizard `Managed`) дренирует outbox с retry/backoff.

### E10. Audit

- Подписаться на `DomainEvent.class` через EventBus, INSERT в `audit.audit_log`.
- Решить про atomicity workflow status (authoring schema) + journal (workflow schema) — open question с E5 §3.5.
- Webhook-ные изменения в catalog.domain / rdm_asset_ownership сейчас НЕ попадают в audit (см. §4 debt).

### E11 / E12 / E13 / E14 — см. SPEC §5.1.

---

## 6. Открытые вопросы (актуальны для команды банка)

Без изменений с E6, плюс:

1. Production-Strategy для Flyway — подтверждено: `autoMigrate=false` в prod, миграции отдельным шагом.
2. Реальные prod-параметры Keycloak (issuer/jwks/audience/client_secret).
3. OM API base URL и bot-токен (для ingestion-коннектора, E12).
4. **HMAC secret rotation policy** — для outbound (E6) И inbound (E7). Минимум: где хранится текущий ключ (Vault path / SOPS key id). Желательно: процесс ротации без downtime + период двух-ключевого verify.
5. Уведомления (e-mail/Slack) approver'ам — V1+.
6. RDM_ADMIN substitution policy — без изменений с E5.
7. Имена env-vars для HMAC: outbound — `RDM_HMAC_KEY`, inbound — `RDM_OM_WEBHOOK_HMAC_KEY`. Согласовать с эксплуатацией.
8. **Webhook URL согласован с OM как `/api/v1/webhooks/om/ownership`?** Если бизнес требует точного `/webhooks/om/ownership` — реструктурировать config (см. §4).
9. **Политика «expert == steward»** (`PostgresOwnershipPort.EXPERT_ACTS_AS_STEWARD = true`). Подтвердить с командой governance: устраивает ли, что каждый OM-expert автоматически становится RDM steward'ом, или steward должны назначаться отдельно.
10. **APPROVER-роль из reviewers**: используется только для read-side (UI «список approver'ов»), workflow её не консультирует. Если бизнес хочет, чтобы reviewers ↔ steward — нужно поменять mapping.

---

## 7. Версия документа

- **0.1** — 2026-05-06. Создан после реализации E7 (Ownership webhook). Build/smoke прогнаны end-to-end в этой же сессии. Автор: Claude Opus 4.7.
