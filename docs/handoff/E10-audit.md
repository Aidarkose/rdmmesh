# Handoff — Эпик E10 (Audit)

> **Аудитория документа.** AI-агенты и инженеры, подключающиеся к проекту после E10. Документ самодостаточен — переписки и контекста предыдущей сессии у вас нет, всё что нужно — здесь, в [`SPEC.md`](../../SPEC.md), [`E1-foundation.md`](E1-foundation.md), [`E2-identity.md`](E2-identity.md), [`E3-catalog.md`](E3-catalog.md), [`E4-authoring.md`](E4-authoring.md), [`E5-workflow.md`](E5-workflow.md), [`E6-publishing.md`](E6-publishing.md), [`E7-ownership.md`](E7-ownership.md), [`E8-distribution.md`](E8-distribution.md) и [`E9-outbound.md`](E9-outbound.md).
>
> **Дата handoff'а.** 2026-05-10.
> **Состояние:** E10 закрыт по содержанию SPEC §5.1 и §3.8 (append-only audit). `make verify` зелёный — **132 теста** (было 125 после E9; +7 AuditEventClassifierTest). End-to-end smoke прошёл по 4-eyes flow + ownership webhook'у — в `audit.audit_log` 6 записей (3×WORKFLOW_TRANSITION, 1×VERSION_PUBLISHED, 2×OWNERSHIP_CHANGED), append-only защита через REVOKE на роль `rdmmesh_app` подтверждена (UPDATE/DELETE → `permission denied`), `verify` endpoint остаётся зелёным.
> **Следующий эпик:** E11 (UI). Указатели — в §5.

---

## 0. TL;DR за 30 секунд

- Реализован модуль `rdmmesh-audit`:
  - **Глобальная подписка** на `EventBus.subscribe(DomainEvent.class, ...)` через `AuditService` — захватывает все три типа событий, которые сейчас идут на bus.
  - INSERT в `audit.audit_log` (миграция V070, append-only INSERT-grants + триггеры BEFORE UPDATE/DELETE/TRUNCATE).
  - Идемпотентность: новый UNIQUE-индекс `audit_log_event_id_uq (event_id, event_type)` (миграция V071) + `ON CONFLICT DO NOTHING` в INSERT'е.
  - Pure mapping `DomainEvent → (event_type, aggregate_type, aggregate_id, actor)` инкапсулирован в `AuditEventClassifier` — 7 unit-тестов.
- Добавлены DomainEvent-обёртки в `rdmmesh-api`:
  - `VersionPublishedDomainEvent` (поверх spec POJO `VersionPublishedEvent`) — публикуется `PublishingService.autoPublish` после успешного CAS OWNER_APPROVED → PUBLISHED.
  - `OwnershipChangedDomainEvent` (поверх `OwnershipChangedEvent`) — публикуется `OwnershipWebhookService.handle` после успешного UPSERT'а в `catalog.domain` или `rdm_asset_ownership`. Несёт уже-resolved `aggregateId/aggregateType` (DOMAIN: catalog.domain.id, CODESET: catalog.code_set.id) — audit не дублирует FQN-парсинг.
- ArchUnit: `audit_only_depends_on_api_or_spec` и `audit_internal_only_used_by_audit` переведены на strict (без `allowEmptyShould`). Заодно `publishing_internal_only_used_by_publishing` — на strict (debt из E9 §4).
- Wire-up в `RdmmeshApplication.run` — одна строка `AuditModule.build(jdbi, eventBus, environment.getObjectMapper())`. PublishingModule и OwnershipModule расширены параметром `EventBus` (в первом — для publish'а VersionPublishedDomainEvent, во втором — для publish'а OwnershipChangedDomainEvent).

---

## 1. Что сделано

### 1.1. Миграция V071

`bootstrap/sql/migrations/audit/V071__audit_log_event_id_unique.sql`:

```sql
CREATE UNIQUE INDEX IF NOT EXISTS audit_log_event_id_uq
    ON audit.audit_log (event_id, event_type);
```

**Зачем пара (event_id, event_type), а не одиночный event_id.** Каждый publisher (workflow/publishing/ownership) генерирует свой `event_id` независимо. Коллизия UUID между ними практически невозможна, но явная дискриминация по типу делает индекс семантически корректным и гарантирует, что вычислимая логика `is_already_audited(event)` работает по полной композиции ключа.

**Зачем вообще UNIQUE.** SyncEventBus синхронен и доставляет событие один раз — но при ручных re-trigger'ах (например, V1+ feature «admin replay» или nightly reconciliation после сбоя) одинаковый event_id может прийти повторно. INSERT с `ON CONFLICT DO NOTHING` гарантирует append-only-семантику без дубликатов.

### 1.2. Новые DomainEvent-обёртки

```
rdmmesh-api/src/main/java/bank/rdmmesh/api/eventbus/
├── DomainEvent.java                       (без изменений)
├── EventBus.java                          (без изменений)
├── WorkflowTransitionDomainEvent.java     (без изменений; поднимался в E5)
├── VersionPublishedDomainEvent.java       ← новый (E10)
└── OwnershipChangedDomainEvent.java       ← новый (E10)
```

Обе обёртки построены по паттерну `WorkflowTransitionDomainEvent` (E5 §1.5): `record(eventId, occurredAt, payload)` поверх spec-POJO. Причина обёртки — `rdmmesh-spec` лежит ниже `rdmmesh-api` в графе зависимостей, spec-POJO не может реализовать `DomainEvent` без цикла.

`OwnershipChangedDomainEvent` дополнительно несёт два поля:

```java
public record OwnershipChangedDomainEvent(
        UUID eventId,
        OffsetDateTime occurredAt,
        OwnershipChangedEvent payload,
        UUID aggregateId,           // resolved id из catalog
        String aggregateType)       // "DOMAIN" | "CODESET"
        implements DomainEvent {}
```

Это знание webhook-сервиса (он распарсил FQN и нашёл CodeSet/Domain в БД); audit получает их готовыми, не повторяя lookup.

### 1.3. Публикация событий

| Источник | Событие | Когда публикуется |
|---|---|---|
| `WorkflowService.transition` | `WorkflowTransitionDomainEvent` | После каждого перехода через REST `/transitions` (без изменений с E5) |
| `PublishingService.autoPublish` | `VersionPublishedDomainEvent` | В конце autoPublish — после CAS OWNER_APPROVED → PUBLISHED, journal'инга и `outbound.enqueueVersionPublished`. Если outbound payload не собрался (например, упало чтение PublishedDetails) — публикуется минимальный event с обязательными полями (event_id, hash, signature, *_id). |
| `OwnershipWebhookService.handleDomain/handleTable` | `OwnershipChangedDomainEvent` | После успешного UPSERT'а domain'а либо `applyChangeEvent` для table. Не публикуется при outcome'ах IGNORED / UNKNOWN_ASSET / UNSUPPORTED — там нет реального изменения состояния. DUPLICATE отсекается ещё до handle (журнал `processed_om_event`), значит и до publish'а. |

Все publish'и обёрнуты в try-catch — сбой `eventBus.publish` (например, при инциденте в audit) логируется, но не откатывает основную операцию. Это согласовано со SPEC §3.8: append-only audit — best-effort гарантия, append-failure не должен блокировать бизнес-операции.

### 1.4. Структура модуля `rdmmesh-audit`

```
rdmmesh-audit/src/main/java/bank/rdmmesh/audit/
├── AuditModule.java                       ← composition factory: build(jdbi, eventBus, json)
└── internal/
    ├── AuditEventClassifier.java          ← pure-mapping DomainEvent → Classification
    ├── AuditService.java                  ← subscribe + serialise + INSERT
    └── dao/
        └── AuditLogDao.java               ← @SqlObject (только @SqlUpdate INSERT + @SqlQuery SELECT)

rdmmesh-audit/src/test/java/bank/rdmmesh/audit/internal/
└── AuditEventClassifierTest.java          ← 7 unit
```

**Pure-mapping AuditEventClassifier** возвращает `Classification(eventType, aggregateType, aggregateId, actor)`:

| DomainEvent | event_type | aggregate_type | aggregate_id | actor |
|---|---|---|---|---|
| `WorkflowTransitionDomainEvent` | `WORKFLOW_TRANSITION` | `VERSION` | `payload.versionId` | `payload.actor` |
| `VersionPublishedDomainEvent` | `VERSION_PUBLISHED` | `VERSION` | `payload.versionId` | `payload.publishedBy` (может быть null) |
| `OwnershipChangedDomainEvent` | `OWNERSHIP_CHANGED` | wrapper.aggregateType | wrapper.aggregateId | **null** (webhook от OM-системы, не от человека) |
| Любой неизвестный `DomainEvent` | `simpleClassName` | null | null | null |

`actor=null` для OWNERSHIP_CHANGED — сознательно: конкретный администратор в OM, который сделал назначение, в payload'е не приходит. Идентификация — по `event_id` (тот же, что в `processed_om_event`) и source IP в access-log'е сервера.

**Сериализация payload'а в `AuditService.extractPayload`** — извлекает оригинальный spec-POJO (тот же, что и body outbound webhook'а). В `audit.audit_log.payload` (jsonb) ложится полный JSON — audit имеет ровно тот контекст, который видели потребители событий, что критично при инциденте reconstruct'а.

**Транзакционная семантика.** Subscriber выполняется синхронно в потоке publisher'а, но в собственной транзакции через `Jdbi.withExtension` (jdbi сам открывает handle на время вызова DAO). Сбой INSERT'а в audit ловится в try-catch и логируется; publisher'у возвращается результат успешно (как и весь EventBus). Это best-effort компромисс пилота: append-failure аудит-БД не должен валить транзитный 4-eyes flow.

### 1.5. ArchUnit изменения

`rdmmesh-app/src/test/java/bank/rdmmesh/arch/ModuleIsolationTest.java`:

| Правило | Было | Стало |
|---|---|---|
| `audit_only_depends_on_api_or_spec` | `allowEmptyShould(true)` | strict |
| `audit_internal_only_used_by_audit` | `internalSliceUsedOnlyBy("audit")` (allow-empty=true) | `internalSliceUsedOnlyByStrict("audit")` |
| `publishing_internal_only_used_by_publishing` | `internalSliceUsedOnlyBy("publishing")` (allow-empty=true) | `internalSliceUsedOnlyByStrict("publishing")` (debt из E9 §4) |

Все 11 ArchUnit-правил теперь strict либо обоснованно non-strict (`catalog`/`authoring`/`identity` пока остаются `internalSliceUsedOnlyBy` — без явного debt'а: правила всё равно проверяют, что в classpath нашлись классы целевого пакета и они не нарушают изоляцию; перевод на strict — micro-cleanup, не блокирует ни один эпик).

### 1.6. Wire-up в `RdmmeshApplication`

```java
// E10 — Audit. Глобальный subscriber на DomainEvent: WorkflowTransition,
// VersionPublished, OwnershipChanged. INSERT в audit.audit_log идёт под
// INSERT-only grants + триггерами против UPDATE/DELETE/TRUNCATE (V070);
// UNIQUE (event_id, event_type) (V071) защищает от replay'ев.
AuditModule.build(jdbi, eventBus, environment.getObjectMapper());
```

PublishingModule.build получил параметр `EventBus eventBus` уже в E5/E6 (для подписки на WorkflowTransition); теперь использует его и для `eventBus.publish(VersionPublishedDomainEvent)`. PublishingService — `EventBus` поле в конструкторе.

OwnershipModule.buildWebhookResource получил **новый** параметр `EventBus eventBus` (между `signingKey` и `json`) — пробрасывается в OwnershipWebhookService.

---

## 2. Smoke (то, что прошло на 2026-05-10)

```bash
# 0. compose down + build (после изменений в коде модуля + V071 миграции)
docker compose -f docker/docker-compose.yml build rdmmesh-service
docker compose -f docker/docker-compose.yml down
docker compose -f docker/docker-compose.yml up -d
# Flyway применил V071, в логе видно:
#  Migrating schema "rdmmesh_meta" to version "071 - audit log event id unique"
#  Successfully applied 1 migration to schema "rdmmesh_meta", now at version v071

# 0a. После рестарта Keycloak генерирует новые sub UUID для тех же usernames;
#     identity.rdm_user_mapping.username UNIQUE даёт коллизию. Перед smoke:
docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -c "TRUNCATE identity.rdm_user_mapping;"
# Этот шаг — не E10-специфичный, актуален для любого re-up'а dev-стека.

# 1. полный 4-eyes flow → auto-publish (как в smoke E5/E6)
T_AUTHOR=$(KC_USER=dev-author make kc-token)
T_STEWARD=$(KC_USER=dev-steward make kc-token)
T_OWNER=$(KC_USER=dev-owner make kc-token)
T_ADMIN=$(KC_USER=dev-admin make kc-token)

DOM=$(curl -sS -X POST -H "Authorization: Bearer $T_ADMIN" -H 'Content-Type: application/json' \
    -d '{"om_domain_id":"e1010101-0000-0000-0000-000000000001","name":"risk_e10","display_name":"Risk E10"}' \
    http://localhost:8080/api/v1/domains | jq -r .id)

CS=$(curl -sS -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' \
    -d '{"name":"ifrs9_stages_e10","display_name":"IFRS9 stages E10","hierarchy_mode":"NONE",
         "initial_schema":{"type":"object","required":["stage"],
            "properties":{"stage":{"type":"string","enum":["1","2","3"]}}}}' \
    http://localhost:8080/api/v1/codesets/by-domain/$DOM | jq -r .id)

V=$(curl -sS -X POST -H "Authorization: Bearer $T_AUTHOR" -H 'Content-Type: application/json' -d '{}' \
    http://localhost:8080/api/v1/versions/by-codeset/$CS | jq -r .id)
# items S1/S2/S3, submit, steward_approve, owner_approve → auto-publish

# 2. audit_log проверка
docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -c "
  SELECT id, event_type, aggregate_type, aggregate_id, actor
    FROM audit.audit_log ORDER BY id;"
#   id |     event_type      | aggregate_type |  aggregate_id  |  actor
#  ----+---------------------+----------------+---------------+---------
#    1 | WORKFLOW_TRANSITION | VERSION        | <V>           | <author>
#    2 | WORKFLOW_TRANSITION | VERSION        | <V>           | <steward>
#    3 | WORKFLOW_TRANSITION | VERSION        | <V>           | <owner>
#    4 | VERSION_PUBLISHED   | VERSION        | <V>           | <owner>

# 3. audit payload — полный VersionPublishedEvent
docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -c "
  SELECT jsonb_pretty(payload) FROM audit.audit_log WHERE id=4;"
# {
#   "version": "0.1.0",
#   "event_id": "...",
#   "domain_id": "...",
#   "codeset_id": "...",
#   "item_count": 3,
#   "version_id": "<V>",
#   "domain_name": "risk_e10",
#   "codeset_name": "ifrs9_stages_e10",
#   "content_hash": "830d5ed5...",
#   "published_at": "2026-05-10T10:50:41.198387Z",
#   "published_by": "<owner-uuid>",
#   "schema_version": 1,
#   "approval_signature": "f8623b9c..."
# }

# 4. ownership webhook (E7) → OWNERSHIP_CHANGED для DOMAIN и CODESET
KEY="rdmmesh-dev-om-webhook-key-change-me-in-prod-vault"
WEBHOOK="http://localhost:8080/api/v1/webhooks/om/ownership"
# domain UPSERT
BODY1='{"event_id":"e10-evt-001","entity_type":"domain","entity_id":"99999999-9999-0000-0000-000000000010","fully_qualified_name":"treasury_e10","occurred_at":"2026-05-10T10:55:00Z","owners":[]}'
SIG1=$(printf '%s' "$BODY1" | python3 -c "import sys,hmac,hashlib; print('sha256='+hmac.new(b'$KEY', sys.stdin.buffer.read(), hashlib.sha256).hexdigest())")
curl -sS -X POST -H "X-OM-Signature: $SIG1" -H 'Content-Type: application/json' --data-binary "$BODY1" "$WEBHOOK"
#  → {"outcome":"APPLIED","event_id":"e10-evt-001","entity_ref":"DOMAIN:<uuid>","note":"domain created"}

# table UPSERT
BODY2="{\"event_id\":\"e10-evt-002\",\"entity_type\":\"table\",\"fully_qualified_name\":\"rdmmesh.risk_e10.ifrs9_stages_e10\",\"occurred_at\":\"2026-05-10T10:55:01Z\",\"owners\":[\"11111111-...\"],\"experts\":[\"22222222-...\"],\"reviewers\":[\"33333333-...\"]}"
SIG2=$(printf '%s' "$BODY2" | python3 -c "...")
curl -sS -X POST -H "X-OM-Signature: $SIG2" -H 'Content-Type: application/json' --data-binary "$BODY2" "$WEBHOOK"
#  → {"outcome":"APPLIED","event_id":"e10-evt-002","entity_ref":"CODESET:<uuid>","summary":"owners(+1/-1) experts(+1/-0) approvers(+1/-0)"}

# audit:
#   5 | OWNERSHIP_CHANGED | DOMAIN  | <domain-id>  |
#   6 | OWNERSHIP_CHANGED | CODESET | <codeset-id> |

# 5. Append-only enforcement: попытка UPDATE/DELETE из rdmmesh_app
docker exec rdmmesh-postgres psql -U rdmmesh_app -d rdmmesh -c "UPDATE audit.audit_log SET event_type='HACKED' WHERE id=1;"
#  → ERROR: permission denied for table audit_log
docker exec rdmmesh-postgres psql -U rdmmesh_app -d rdmmesh -c "DELETE FROM audit.audit_log WHERE id=1;"
#  → ERROR: permission denied for table audit_log
# REVOKE UPDATE,DELETE,TRUNCATE на роль rdmmesh_app (V070) работает.

# 6. Idempotency на уровне webhook'а
curl -sS -X POST -H "X-OM-Signature: $SIG1" --data-binary "$BODY1" "$WEBHOOK"
#  → {"outcome":"DUPLICATE","event_id":"e10-evt-001"}
# audit count для OWNERSHIP_CHANGED остаётся 2 — re-trigger ownership webhook'а
# отсекается processed_om_event ДО publish'а DomainEvent.

# 7. Verify endpoint остаётся зелёным после E10
TOK=$(KC_USER=dev-author make kc-token)
VID=$(docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -tAc "SELECT id FROM authoring.code_set_version WHERE status='PUBLISHED' ORDER BY published_at DESC LIMIT 1;")
curl -sS -H "Authorization: Bearer $TOK" "http://localhost:8080/api/v1/versions/$VID/verify"
#  → {"applicable":true,"verified":true,"computedHash":"...","storedHash":"...","note":null}
```

### `make verify`

Зелёный, **132 теста** (было 125 после E9 → +7 AuditEventClassifierTest):
- 7 AuditEventClassifierTest (E10) ← новые
- 22 StateMachineTest (E5/E6)
- 31 authoring (SemVer/KeyEncoding/AttributesValidator/DiffCalculator/CsvBulkParser, E4)
- 8 JwtValidatorTest (E2)
- 12 ownership (6 HmacVerifierTest + 6 FqnParserTest, E7)
- 17 distribution (11 VersionResolverTest + 6 KeyEncodingTest, E8)
- 24 publishing-outbound (5 BackoffTest + 9 SubscriptionFilterMatcherTest + 6 WebhookHmacTest + 4 EnvWebhookKeyAdapterTest, E9)
- 11 ArchUnit (3 strict-перевода в E10: audit + publishing internal slices + audit_only_depends_on_api_or_spec)

---

## 3. Что осталось доделать в E10 — мягкие follow-up'ы

Ничего не блокирует E11. Список того, к чему **нужно вернуться** позже:

1. **Криптографическая audit-цепочка (V14 / Compliance hardening, SPEC §3.8).** Колонки `prev_hash`/`entry_hash` в `audit.audit_log` подготовлены ещё миграцией V070, но не наполняются. Алгоритм планируется простой: `entry_hash = sha256(prev_hash || event_id || event_type || payload)`, `prev_hash = entry_hash` предыдущей записи в порядке `id ASC`. Это даёт hash-chain — компрометация одной записи разваливает все последующие верификации. Нужно: миграция V072 на тот же индекс, расширение `AuditService.onEvent` (или background-batch'ер, чтобы не сериализовывать insert'ы) + REST endpoint `/audit/verify-chain`. Релевантно к E14.
2. **Параллельный сток в S3 immutable bucket (SPEC §3.8 «V2»).** На пилоте достаточно одной таблицы. Когда retention 7 лет начнёт раздувать БД — добавить background job, который батчит старые записи в S3 (с object-lock'ом) и отрезает их из `audit_log` (но это уже не append-only — нужен отдельный аналог DELETE через политику архивации). Этот выход — отдельный ADR.
3. **Audit-export REST endpoint.** SPEC §3.3 «audit» включает «append-only журнал, подпись, экспорт». Сейчас экспорт через `psql` / прямой SELECT. Полезно: `GET /api/v1/audit?from=...&to=...&event_type=...&aggregate_id=...&page=...` под `@RolesAllowed("RDM_ADMIN")` либо через отдельную read-роль `RDM_AUDITOR`. Read-DAO `findByEvent` уже есть в `AuditLogDao` — расширить до постраничного listing'а тривиально.
4. **Метрики subscriber'а.** Сейчас sync-доставка может тихо «терять» события на стороне audit при сбое БД (ловится try-catch + log.warn). Полезно подключить Dropwizard meters: `audit.events.received{event_type=...}`, `audit.events.persisted`, `audit.events.failed`. На пилоте — log-grep, в проде — Prometheus.
5. **Тест на полный путь EventBus → AuditService → DAO через testcontainers.** Сейчас покрытие — pure unit на classifier'е + e2e smoke. Промежуточный слой («подписка зарегистрирована, INSERT с правильным payload'ом») закрывается ArchUnit'ом + e2e, но при росте кода стоит добавить IT-тест с Postgres testcontainer'ом. Ровно тот же мягкий debt, что был у E3/E4/E5.
6. **Reconciliation между `workflow.workflow_transition` и `audit.audit_log`.** Это два независимых журнала; должны совпадать по записям WORKFLOW_TRANSITION. Background job, сравнивающий counts по version_id, может ловить рассогласования (например, если SyncEventBus упустил событие из-за RuntimeException в другом subscriber'е — он логируется, но событие текущему subscriber'у уже доставлено). На пилоте — manual SELECT-cross-check; в V1+ — автоматизировать.
7. **`IdempotencyTokenInterceptor` для PublishingService re-publish.** Сейчас `autoPublish.SKIPPED` срабатывает, когда CAS не прошёл (статус уже не OWNER_APPROVED). Но если в будущем появится «admin replay» через явный API endpoint, нужен явный токен идемпотентности — иначе при сетевой ретрае админ может получить два PUBLISHED-события с разными `event_id` (UNIQUE на audit_log не сработает). Это уже задача E14, не E10.

---

## 4. Технический долг и решения, повлиявшие на следующие эпики

| Что | Где | Когда снять / следующий шаг |
|---|---|---|
| Sync EventBus subscriber может «потерять» событие при сбое audit-БД (ловится try-catch + log.warn) | `AuditService.onEvent` | E14 — добавить outbox-pattern либо async background-flusher, если уровень потерь окажется неприемлемым. На пилоте sync best-effort достаточно. |
| Audit-INSERT идёт в собственной транзакции, отдельной от publish'а / workflow CAS | `AuditService.onEvent` | Тот же atomic-debt, что у E5 §1.4 / E6 §3 #1 / E9 §4. Решать одним пакетом в E14. |
| `actor=null` для OWNERSHIP_CHANGED | `AuditEventClassifier.classifyOwnership` | Когда OM-Event Subscription начнёт включать `userName`/`updatedBy` — расширить `OwnershipChangedEvent.json` schema'у новым полем и зарезолвить через `rdm_user_mapping`. Сейчас OM-вебхук не несёт этот атрибут. |
| Канонический payload для audit = оригинальный spec-POJO без сортировки ключей | `AuditService.extractPayload` + `ObjectMapper` | jsonb в Postgres всё равно нормализует представление при записи. Для audit это не критично (читать будем через `payload->>'field'`). Если когда-то потребуется byte-stable форма audit-записи (для hash-chain V14) — переключиться на `ORDER_MAP_ENTRIES_BY_KEYS` + `text` колонку, как сделано в E9 для `webhook_outbox.payload`. |
| `VERSION_DEPRECATED` audit-event'а нет | `PublishingService.autoPublish` | E6 auto-DEPRECATE предыдущей версии записывается только в `workflow.workflow_transition` как system-action, а не публикуется в EventBus. Если бизнесу нужна история «когда какая версия стала DEPRECATED» — добавить `VersionDeprecatedDomainEvent` + JSON Schema `version-deprecated-event.json` (handoff E9 §3 #1 уже это упомянул). |
| `simpleClassName` fallback для неизвестных DomainEvent | `AuditEventClassifier.classify` | Сознательно — гарантирует, что audit не молчит при будущих типах событий. Если хочется строго запретить — заменить на throw IllegalArgumentException. Не делать без явной просьбы. |

---

## 5. Указатели на следующие эпики

> Конкретное содержание — в SPEC §5.1.

### E11. UI (следующий)

- **Где:** `rdmmesh-ui` (placeholder). React 18 + TypeScript 5 + AntD 4.24 + TanStack Table + react-i18next + Vite (SPEC §3.1).
- **Что реализовать:** Catalog tree, CodeSet view, Items grid editor (TanStack), Versions list, version Diff, My Tasks (REST `/tasks/my`), Subscription management (E9 admin-экран). OIDC flow через Keycloak `rdmmesh-ui` client (PKCE).
- **TS-кодогенерация** из JSON Schema'ы (handoff E1 §3.8) — `json-schema-to-typescript` в `rdmmesh-spec/codegen/typescript/`. Подключение к существующим `entity/*.json` + `events/*.json` тривиально. Сейчас в `rdmmesh-spec` codegen есть только под Java (jsonschema2pojo).
- **Audit-экран** (SPEC §3.3 «экспорт audit») — может появиться сразу либо отдельным шагом V1+ (см. §3 follow-up #3).

### E12. Ingestion-коннектор

- **Где:** отдельный репозиторий `om-rdmmesh-source`, **не часть** `rdmmesh`. Python-пакет, деплоится в OM Airflow (SPEC §3.6).
- Маппинг: `Domain → OM Domain`, `CodeSet → OM Table`, attributes → columns. Pull со стороны OM, REST в `rdmmesh-api` (SPEC §3.6).
- Pydantic-модели — codegen из тех же JSON Schema'ов (handoff E1 §3.8), скрипт `rdmmesh-spec/codegen/pydantic/`.

### E13. Bitemporal & Hierarchy

- Closure table rebuild через триггеры/batch'и (handoff E4 §3 #1) для крупных draft'ов.
- Tree-редактор для Security/Access Matrix (handoff E1 §6 «E13»).
- Полный UI для `as_of`/`knowledge_as_of` параметров (handoff E8).

### E14. Compliance hardening

- Криптографическая audit-цепочка (см. §3 #1).
- Audit-export в S3 immutable bucket (SPEC §3.8 «V2»).
- Verify-endpoint, который пересчитывает не только SHA-256, но и HMAC (handoff E6 §3 #3).
- Унифицированное atomic-decision для split-tx случаев E5/E6/E7/E9/E10 (см. §4 debt).
- Security review (OWASP Top 10).

---

## 6. Открытые вопросы (актуальны для команды банка)

Без изменений с E9, плюс:

1. Production-Strategy для Flyway — подтверждено: `autoMigrate=false` в prod.
2. Реальные prod-параметры Keycloak (issuer/jwks/audience/client_secret).
3. OM API base URL и bot-токен.
4. HMAC secret rotation policy — outbound (E6) / inbound (E7) / per-subscription (E9).
5. Уведомления (e-mail/Slack) approver'ам — V1+.
6. RDM_ADMIN substitution policy.
7. Имена env-vars для HMAC: outbound `RDM_HMAC_KEY` (E6), inbound `RDM_OM_WEBHOOK_HMAC_KEY` (E7), per-subscription `RDM_WEBHOOK_KEY_*` (E9).
8. Webhook URL OM согласован с `/api/v1/webhooks/om/ownership`?
9. Политика «expert == steward».
10. APPROVER mapping из reviewers.
11. Distribution — нужны ли HTTP cache headers / rate-limit?
12. `/subscriptions` — нужен ли domain-scoped RBAC?
13. Список зарегистрированных consumer'ов и их `secret_id`.
14. **Audit retention policy implementation.** SPEC §3.7 — 7 лет. Сейчас retention никак не enforced (нет retention-job). Нужно ADR: где живёт логика — в Postgres partitioning по `occurred_at` (LIST PARTITION) либо в S3-archival job (см. §3 follow-up #2). До решения retention-вопроса prod-deploy E10 запускать можно — данные растут линейно, 7 лет с пилотного объёма ≈ десятки МБ.
15. **Audit-доступ.** SPEC §3.3 говорит «audit подписан на event-bus, никого не читает», но read-доступ кому-то нужен (compliance team, RDM_ADMIN). Кому именно? — запрос через psql (DBA-доступ), отдельный read-only REST endpoint под `RDM_AUDITOR`-ролью (новая base-роль в Keycloak realm), либо открыть через `RDM_ADMIN`. Решить с командой compliance до прихода первого аудитора.
16. **`actor=null` для OWNERSHIP_CHANGED.** Если бизнес настаивает на полной идентификации actor'а в audit — нужно расширить OM Event Subscription payload полем `updatedBy` (требует изменений на стороне OM). До этого момента источник изменения — только `event_id` + source IP в access-log'е.

---

## 7. Версия документа

- **0.1** — 2026-05-10. Создан после реализации E10 (Audit). Build/smoke прогнаны end-to-end в этой же сессии. Автор: Claude Opus 4.7.
