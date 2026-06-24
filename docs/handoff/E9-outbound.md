# Handoff — Эпик E9 (Outbound webhooks)

> **Аудитория документа.** AI-агенты и инженеры, подключающиеся к проекту после E9. Документ самодостаточен — переписки и контекста предыдущей сессии у вас нет, всё что нужно — здесь, в [`SPEC.md`](../../SPEC.md), [`E1-foundation.md`](E1-foundation.md), [`E2-identity.md`](E2-identity.md), [`E3-catalog.md`](E3-catalog.md), [`E4-authoring.md`](E4-authoring.md), [`E5-workflow.md`](E5-workflow.md), [`E6-publishing.md`](E6-publishing.md), [`E7-ownership.md`](E7-ownership.md) и [`E8-distribution.md`](E8-distribution.md).
>
> **Дата handoff'а.** 2026-05-06.
> **Состояние:** E9 закрыт по содержанию SPEC §5.1. `make verify` зелёный — **125 тестов** (было 101 после E8; +24 новых: 5 BackoffTest, 9 SubscriptionFilterMatcherTest, 6 WebhookHmacTest, 4 EnvWebhookKeyAdapterTest). End-to-end smoke по трём сценариям прошёл (см. §3).
> **Следующий эпик:** E10 (Audit). Указатели — в §5.

---

## 0. TL;DR за 30 секунд

- Реализован модуль E9 поверх существующего `rdmmesh-publishing` (миграция V040 уже создавала `webhook_subscription` + `webhook_outbox`, новая V041 — переключение `payload jsonb → text`, см. §1.6).
- Реализован `OutboundPort.enqueueVersionPublished(VersionPublishedEvent)`:
  - после `lifecycle.publish` `PublishingService` строит payload, вызывает `OutboxOutboundAdapter.enqueue`;
  - адаптер сериализует payload в стабильный JSON (Jackson `ORDER_MAP_ENTRIES_BY_KEYS` + `@JsonPropertyOrder` POJO), считает HMAC-SHA-256 с per-subscription ключом, INSERT'ит в `webhook_outbox` для каждой active subscription, чей фильтр совпал.
  - **транзакция enqueue отдельная** от publish — best-effort split, как и в E5/E6 (см. §4 debt).
- REST CRUD: `GET/POST/DELETE /api/v1/subscriptions` под `@RolesAllowed("RDM_ADMIN")`. SoftDelete (active=false), не физическое удаление.
- `WebhookDeliveryWorker` — Dropwizard `Managed`, `ScheduledExecutorService` с tick=5s, `FOR UPDATE SKIP LOCKED LIMIT 10`, JDK `HttpClient`, headers `X-RDM-Event-Id`, `X-RDM-Event-Type`, `X-RDM-Signature: sha256=<hex>`. Backoff 30s/1m/2m/5m/15m/30m/1h/2h, после 8 попыток give-up.
- Новый порт `WebhookKeyPort.resolveKey(secretId) → byte[]`, реализация `EnvWebhookKeyAdapter`: env `RDM_WEBHOOK_KEY_<UPPER(secretId)>`, fallback на общий dev-key. Vault — drop-in замена адаптера в проде.
- Расширены `CatalogReadPort` (`findDomain`) и `VersionLifecyclePort` (`findPublishedDetails`) — нужны publishing'у для построения payload без cross-module imports.

---

## 1. Что сделано

### 1.1. Новые файлы

```
rdmmesh-api/src/main/java/bank/rdmmesh/api/port/
  └── WebhookKeyPort.java                          ← новый порт: secret_id → ключ

rdmmesh-publishing/src/main/java/bank/rdmmesh/publishing/
  ├── PublishingModule.java                        ← новый build(...) с outbound + worker + sub
  ├── internal/outbound/
  │   ├── OutboxOutboundAdapter.java               ← реализация OutboundPort
  │   ├── SubscriptionService.java                 ← CRUD subscriptions
  │   ├── SubscriptionFilterMatcher.java           ← pure-логика match'а фильтра
  │   ├── WebhookDeliveryWorker.java               ← Dropwizard Managed worker
  │   ├── WebhookHmac.java                         ← sign + verify helper
  │   ├── Backoff.java                             ← пуре-функция расписания
  │   └── dao/
  │       ├── SubscriptionDao.java                 ← @SqlObject DAO
  │       └── WebhookOutboxDao.java                ← @SqlObject DAO + claimDue с SKIP LOCKED
  ├── resource/
  │   ├── SubscriptionResource.java                ← GET/POST/DELETE /api/v1/subscriptions
  │   └── SubscriptionDto.java                     ← wire-DTO

rdmmesh-app/src/main/java/bank/rdmmesh/app/security/
  └── EnvWebhookKeyAdapter.java                    ← env-based реализация WebhookKeyPort

bootstrap/sql/migrations/publishing/
  └── V041__webhook_outbox_payload_text.sql        ← payload jsonb → text (см. §1.6)
```

Тесты:
```
rdmmesh-publishing/src/test/java/bank/rdmmesh/publishing/internal/outbound/
  ├── BackoffTest.java                              ← 5 unit
  ├── SubscriptionFilterMatcherTest.java            ← 9 unit
  └── WebhookHmacTest.java                          ← 6 unit
rdmmesh-app/src/test/java/bank/rdmmesh/app/security/
  └── EnvWebhookKeyAdapterTest.java                 ← 4 unit
```

### 1.2. Изменённые файлы

| Файл | Что |
|---|---|
| `rdmmesh-api/.../CatalogReadPort.java` | +`findDomain(domainId)` + record `DomainSnapshot(id, name, displayName)`. |
| `rdmmesh-api/.../VersionLifecyclePort.java` | +`findPublishedDetails(versionId)` + record `PublishedVersionDetails`. |
| `rdmmesh-catalog/.../CatalogReadAdapter.java` | реализация `findDomain` через `DomainDao.findById`. |
| `rdmmesh-authoring/.../VersionLifecycleAdapter.java` | реализация `findPublishedDetails`. |
| `rdmmesh-publishing/.../PublishingService.java` | +поле `OutboundPort outbound`, после publish'а — `buildPublishedEvent` + `outbound.enqueueVersionPublished`. Сбой enqueue — лог-warning, версия PUBLISHED не откатывается. |
| `rdmmesh-publishing/pom.xml` | +`jdbi3-sqlobject`, +`dropwizard-lifecycle`. |
| `rdmmesh-app/.../RdmmeshApplication.java` | wiring `WebhookKeyPort` через `EnvWebhookKeyAdapter`, регистрация `subscriptions()` resource'а и `manage(deliveryWorker)`. |

### 1.3. Контракт `OutboundPort`

```java
void enqueueVersionPublished(VersionPublishedEvent event);
```

Семантика:
1. Сериализовать payload через Jackson (`@JsonPropertyOrder` POJO + `ORDER_MAP_ENTRIES_BY_KEYS` для возможных Map'ов внутри). Бинарный output **сохраняется** ровно тот, что попадёт в HTTP body.
2. Найти все active subscription'ы и отфильтровать через `SubscriptionFilterMatcher`.
3. Для каждой попавшей subscription — резолвить ключ через `WebhookKeyPort`, посчитать HMAC-SHA-256(payload, key), INSERT'нуть в outbox с UNIQUE-индексом `(subscription_id, event_id)` (`ON CONFLICT DO NOTHING` — идемпотентность ре-publish).
4. Worker позже подхватывает строки и доставляет.

**Транзакция** одна на всю пачку INSERT'ов — это `jdbi.useTransaction`. Отделена от транзакции publish'а в authoring (см. §4 debt). На пилоте — best-effort split, общая позиция со всеми async-цепочками E5/E6/E7.

### 1.4. SubscriptionFilterMatcher (pure-логика)

Фильтр (rdmmesh-spec/schema/api/webhook-subscription.json):

```json
{
  "filter": {
    "domains":  ["risk", "treasury"],     // OR
    "codesets": ["ifrs9_stages"],         // OR
    "events":   ["VERSION_PUBLISHED"]     // OR
  }
}
```

Семантика «AND по полям, OR внутри поля»:
- пустой/отсутствующий список → нет ограничения по этому полю;
- непустой → событие должно совпасть хотя бы с одним элементом.

Сравнение точечное (`equals`), формат `qualified_name` (lower snake_case) задаётся spec'ом — иные форматы в БД не появляются.

Парсинг JSON фильтра идёт через тот же `ObjectMapper`, и **fail-safe** при битом JSON: возвращаем «no-op-фильтр» (доставить, чем тихо потерять). Ошибочная конфигурация subscription'а не маскируется — следы в логах worker'а / при `GET /subscriptions/{id}`.

### 1.5. Backoff

```
attempts=1 →  30s
        2 →   1m
        3 →   2m
        4 →   5m
        5 →  15m
        6 →  30m
        7 →   1h
        8 →   2h     (последняя retry-попытка)
       ≥MAX_ATTEMPTS=8 → give up
```

При исчерпании worker помечает строку как `delivered_at = now() + last_error = "GIVE_UP after N attempts: …"` — это останавливает ретраи (запрос `delivered_at IS NULL` уже не вернёт строку). Манyally re-trigger — V1+ feature; на пилоте delete+create-subscription и новый publish.

### 1.6. Миграция V041 (payload jsonb → text)

**Зачем:** PostgreSQL JSONB **нормализует** представление при записи (сортировка ключей, удаление whitespace, дедуп). После round-trip байты НЕ совпадают с теми, по которым считалась HMAC-подпись, и receiver выдаёт signature mismatch. На первом smoke это и поймали (см. §3.1).

`webhook_outbox.payload` теперь `text` — байт-в-байт идентично enqueue → выдаче. Нам не нужны индексы / JSONB-операторы по этой колонке (внутренняя очередь воркера, не аналитический объект), поэтому переключение безопасно.

```sql
ALTER TABLE publishing.webhook_outbox
    ALTER COLUMN payload TYPE text USING payload::text;
```

### 1.7. WebhookDeliveryWorker (Dropwizard Managed)

```
start():
  ScheduledExecutorService.scheduleWithFixedDelay(safeTick, 5s, 5s)

drainOnce():
  jdbi.useTransaction:
    rows = WebhookOutboxDao.claimDue(LIMIT 10)   // FOR UPDATE SKIP LOCKED
    for each row:
      sub = SubscriptionDao.findById(row.subscriptionId)
      if not sub or !sub.active → markGivenUp
      deliver(row, sub):
        HTTP POST sub.url + headers + signed body
        2xx → markDelivered + sub.markDelivery(OK)
        else / IOException / timeout:
          attempts = row.attempts + 1
          if exhausted(attempts) → markGivenUp + sub.markDelivery(FAILED)
          else → markRetry(now + backoff(attempts), error) + sub.markDelivery(RETRYING)
  COMMIT
```

**Lock duration:** POST'ы делаются ВНУТРИ транзакции, пока row-locks удерживаются. На пилоте N=10 × 10s timeout = max 100s — приемлемо. Многореплика V1+ → переход на lease-based (UPDATE next_attempt_at в короткой tx, POST вне транзакции).

**HTTP-клиент:** JDK `HttpClient` (без Jersey-client), connect timeout 5s, request timeout 10s, `Redirect.NEVER`. Headers:
- `Content-Type: application/json`
- `X-RDM-Event-Id`     — UUID, ключ идемпотентности для consumer'а
- `X-RDM-Event-Type`   — "VERSION_PUBLISHED" (E9 scope)
- `X-RDM-Signature: sha256=<hex>` — HMAC-SHA-256(payload, per-subscription key)

### 1.8. EnvWebhookKeyAdapter

Резолвит pointer → ключ через переменные окружения по правилу:

```
specific = System.getenv("RDM_WEBHOOK_KEY_" + UPPER(secretId))
default  = System.getenv("RDM_WEBHOOK_KEY_DEFAULT")
fallback = в коде fromDevFallback(...)
→ first non-blank wins
```

Минимум 32 байта. В dev `RdmmeshApplication` использует fallback `rdmmesh-dev-webhook-key-change-me-in-prod-vault`. В проде — выставить per-subscription env-vars или подменить адаптер на `VaultWebhookKeyAdapter`.

> **Имена env-vars** (E9-добавления): `RDM_WEBHOOK_KEY_*`. Согласовать с эксплуатацией к моменту prod-deploy. Параллель с E6 (`RDM_HMAC_KEY` — server snapshot key) и E7 (`RDM_OM_WEBHOOK_HMAC_KEY` — inbound OM webhook).

### 1.9. REST контракт `/subscriptions`

```
GET    /api/v1/subscriptions          @RolesAllowed("RDM_ADMIN")
GET    /api/v1/subscriptions/{id}     @RolesAllowed("RDM_ADMIN")
POST   /api/v1/subscriptions          @RolesAllowed("RDM_ADMIN")
DELETE /api/v1/subscriptions/{id}     @RolesAllowed("RDM_ADMIN")
```

POST-body:
```json
{
  "url":       "https://consumer.bank/webhooks/rdm",  // http(s) только
  "secret_id": "primary",                              // pointer (Vault path)
  "filter":    { "domains":[...], "codesets":[...], "events":[...] },
  "active":    true                                    // default true
}
```

GET-response:
```json
{
  "id": "uuid", "url": "...", "secret_id": "primary",
  "filter": {...}, "active": true,
  "created_at": "instant", "created_by": "om_user_id",
  "last_delivery_at": "instant", "last_delivery_status": "OK|FAILED|RETRYING"
}
```

**Возвращаем `secret_id`, НЕ сам секрет** (SPEC §3.5). DELETE — soft (active=false), физически строка остаётся (на неё держат FK строки `webhook_outbox` через `ON DELETE CASCADE` — но отказа от physical-delete достаточно для аудита).

---

## 2. Smoke (то, что прошло на 2026-05-06)

Полный сценарий — `/tmp/e9_smoke.sh`, `/tmp/e9_retry.sh`, `/tmp/e9_filter.sh` (не комитятся). Receiver — `/tmp/webhook_receiver.py`, простой Python HTTP-server на порту 9099, который проверяет HMAC и логирует payload.

### 2.1. Happy path (delivered_at IS NOT NULL, HMAC verified=True)

```bash
make up
# Запускаем receiver на хосте: python3 /tmp/webhook_receiver.py 9099 &
# Из контейнера host.docker.internal:9099 доступен (verified)

ADMIN=$(KC_USER=dev-admin make kc-token)
AUTHOR=$(KC_USER=dev-author make kc-token)

# 1. domain + codeset
DOM=$(curl -X POST -H "Authorization: Bearer $ADMIN" .../domains | jq -r .id)
CS=$(curl -X POST -H "Authorization: Bearer $AUTHOR" .../codesets/by-domain/$DOM | jq -r .id)

# 2. POST /subscriptions (admin)
curl -X POST -H "Authorization: Bearer $ADMIN" -d '{
  "url":"http://host.docker.internal:9099/webhook",
  "secret_id":"primary",
  "filter":{"events":["VERSION_PUBLISHED"]}
}' .../subscriptions
# → 201, id=...

# 3. POST as author → 403 (RBAC)
# 4. POST без url → 400 (validation)

# 5. полный 4-eyes flow → auto-publish
# 6. через 5s worker tick — payload доставлен:

#   webhook_outbox: attempts=0, delivered=t
#   subscription:    last_delivery_status=OK
#   receiver log:    verified=True
```

### 2.2. Retry (dead URL)

```
URL = http://host.docker.internal:1/dead
после первого tick'а:
  webhook_outbox: attempts=1, delivered=f, next_attempt_at=now()+22s, last_error="ConnectException: null"
  subscription:    last_delivery_status=RETRYING
```

Backoff корректный: 30s − 8s ожидания tick'а ≈ 22s до следующей попытки.

### 2.3. Filter (mismatched domain)

```
filter = {"domains":["other_domain"]}
после publish'а в risk_e9:
  webhook_outbox WHERE subscription_id = $SUB → 0 rows
```

Фильтр evaluate'ится на enqueue, не на delivery — корректно.

### 2.4. `make verify`

**125 тестов** (было 101 после E8 → +24 новых):
- 5 BackoffTest (E9)
- 9 SubscriptionFilterMatcherTest (E9)
- 6 WebhookHmacTest (E9)
- 4 EnvWebhookKeyAdapterTest (E9)
- 11 ArchUnit (без изменений)
- 8 JwtValidatorTest (E2)
- 31 authoring (E4)
- 22 StateMachineTest (E5/E6)
- 12 ownership (E7)
- 17 distribution (E8)

---

## 3. Что осталось доделать в E9 — мягкие follow-up'ы

Ничего не блокирует E10. Список того, к чему **нужно вернуться** позже:

1. **`VersionDeprecatedEvent` outbound.** SPEC §2.2 этап 5 (DEPRECATED) — `SubscriptionFilter.events` уже знает `VERSION_DEPRECATED`. На E9 outbox этим типом не наполняется (handoff E6 §1.5 — auto-deprecate происходит при publish'е новой версии). Закрыть с E10 или отдельной короткой эпике: добавить `OutboundPort.enqueueVersionDeprecated(VersionDeprecatedEvent)`, ввести JSON Schema `version-deprecated-event.json` в `rdmmesh-spec/schema/events/`, расширить `PublishingService.autoPublish` дополнительным enqueue для prev-версии.
2. **Manual re-trigger для GIVE_UP-строк.** Сейчас после исчерпания попыток админ только видит `last_error` в `webhook_outbox` и `last_delivery_status=FAILED` на subscription'е. Reissue — через DELETE (soft-deactivate) + создание новой subscription и новый publish. Полезно добавить admin-endpoint `POST /api/v1/subscriptions/{id}/replay?event_id=...` (V1+).
3. **Многореплика worker'а.** Сейчас POST идёт **внутри** транзакции SKIP LOCKED — lock висит до commit'а. На single-node OK; при многореплике стоит перейти на lease-based (UPDATE `next_attempt_at = now()+lease`, COMMIT, потом POST вне tx). См. handoff E5 §1.4 для родственного debt.
4. **HMAC key rotation.** Сейчас `WebhookKeyPort.resolveKey()` отдаёт один ключ на subscription. При rotation потребуется поддержка двух-ключевой verify-фазы — но это ответственность consumer'а, не RDM. Для prod-readyness: документировать процесс ротации (создать новую subscription с новым `secret_id`, перевести consumer на двух-ключевую verify, удалить старую subscription).
5. **`X-RDM-Subscription-Id` header.** Полезно для consumer'а: один URL может обслуживать несколько subscription'ов (например, два фильтра — две подписки). Сейчас этого нет; добавить тривиально.
6. **OpenAPI спека endpoint'ов.** `/subscriptions` тоже хочется в OpenAPI 3.1 dump'е (handoff E3 §3 #3). Сделать с E11 (UI), сразу для всех endpoint'ов.
7. **Health-check «есть ли застрявшие в outbox».** Прямо сейчас не выставлен; для оператора полезно — `count(*) FROM webhook_outbox WHERE delivered_at IS NULL AND attempts >= 3` поверх Dropwizard healthcheck, чтобы попадало в стандартный `/healthcheck` JSON.
8. **Streaming-доставка при больших payload'ах.** Сейчас payload-bytes держатся в heap дважды (Java + JDBC). Для пилотных VersionPublishedEvent (~1 KB) ОК. Если добавится массивный «inline-snapshot» payload — пересмотреть.

---

## 4. Технический долг и решения, повлиявшие на следующие эпики

| Что | Где | Когда снять / следующий шаг |
|---|---|---|
| `OutboundPort.enqueueVersionPublished` идёт во ВТОРОЙ транзакции относительно `lifecycle.publish` | `PublishingService.autoPublish` | E14 (compliance hardening) — общий пакет для split-tx случаев E5/E6/E7/E9. Решение: либо общий `Jdbi.handle` поверх двух schemas, либо outbox-pattern с idempotent processor'ом ровно при publish-CAS. |
| `webhook_outbox.payload` теперь `text`, а не `jsonb` | V041 миграция | **Не снимать.** Это правильное решение для byte-stable storage; jsonb сломал бы HMAC-семантику. |
| Worker держит row-lock пока выполняет POST | `WebhookDeliveryWorker.drainOnce` | При переходе на multi-replica deploy — switch на lease-based (см. §3 #3). |
| Dev fallback HMAC-key зашит в `RdmmeshApplication.run` | `EnvWebhookKeyAdapter.withDevFallback("...")` | В prod-конфиге убрать fallback, выставить `RDM_WEBHOOK_KEY_*` обязательными env-vars или Vault-путь. |
| `VersionDeprecatedEvent` пока не отправляется | `PublishingService.autoPublish` | Follow-up E9 / V1: см. §3 #1. |
| ArchUnit `publishing_internal_only_used_by_publishing` остался в `internalSliceUsedOnlyBy` (без strict) | `ModuleIsolationTest` | Перевести на `internalSliceUsedOnlyByStrict("publishing")` — в `publishing/internal/` теперь много классов; правило безопасно делать строгим. **Сделать в E10** одним коммитом со снятием `audit_only_*`-rules. |

---

## 5. Указатели на следующие эпики

> Конкретное содержание — в SPEC §5.1.

### E10. Audit (следующий)

- **Где:** `rdmmesh-audit`. Сейчас пуст (`pom.xml` + `src/main/java/.gitkeep`).
- **Что реализовать:**
  - Подписаться на `EventBus.subscribe(DomainEvent.class, ...)` глобально, INSERT в `audit.audit_log` через JDBI.
  - Сейчас публикуется только `WorkflowTransitionDomainEvent`. **Добавить** `VersionPublishedDomainEvent` (обёртка над spec-POJO `VersionPublishedEvent`) и `OwnershipChangedDomainEvent` (обёртка над spec-POJO `OwnershipChangedEvent`) — иначе audit пропустит publish-события (E6) и webhook-события (E7). Handoff E6 §3 #5 и E7 §4 это упомянули.
  - Снять `allowEmptyShould(true)` с `audit_only_depends_on_api_or_spec` и `audit_internal_only_used_by_audit`. Заодно перевести `publishing_internal_only_used_by_publishing` на strict (см. §4 debt).
- **Зависимости:** E1 ✓ (миграция V070 + INSERT-only privileges + триггеры BEFORE UPDATE/DELETE/TRUNCATE).

### E11. UI

- **Где:** `rdmmesh-ui` (placeholder). React + TS + AntD + TanStack Table + Vite.
- Экраны: Catalog tree, CodeSet view, Items grid editor, Versions list, Diff, My Tasks. Subscription management (E9) — отдельный admin-экран.
- TS-кодогенерация из JSON Schema'ы — handoff E1 §3.8.

### E12 / E13 / E14 — см. SPEC §5.1.

---

## 6. Открытые вопросы (актуальны для команды банка)

Без изменений с E8, плюс:

1. Production-Strategy для Flyway — подтверждено: `autoMigrate=false` в prod.
2. Реальные prod-параметры Keycloak (issuer/jwks/audience/client_secret).
3. OM API base URL и bot-токен.
4. HMAC secret rotation policy — outbound (E6) / inbound (E7) / **per-subscription (E9)**. Согласовать единый процесс ротации с эксплуатацией.
5. Уведомления (e-mail/Slack) approver'ам — V1+.
6. RDM_ADMIN substitution policy.
7. Имена env-vars для HMAC: outbound `RDM_HMAC_KEY` (E6), inbound `RDM_OM_WEBHOOK_HMAC_KEY` (E7), per-subscription `RDM_WEBHOOK_KEY_*` (E9, E9-add).
8. Webhook URL OM согласован с `/api/v1/webhooks/om/ownership`?
9. Политика «expert == steward».
10. APPROVER mapping из reviewers.
11. Distribution — нужны ли HTTP cache headers / rate-limit?
12. **`/subscriptions` — нужен ли domain-scoped RBAC (admin своего домена) вместо глобального `RDM_ADMIN`?** На пилоте только два домена и операционно достаточно глобального admin'а; при росте до десятков доменов — пересмотреть.
13. **Список зарегистрированных consumer'ов и их `secret_id`** — прийдёт от команд Risk-engine / IGA / BI до prod-deploy. Без этого первые subscription'ы создавать некому.

---

## 7. Версия документа

- **0.1** — 2026-05-06. Создан после реализации E9 (Outbound webhooks). Build/smoke прогнаны end-to-end в этой же сессии. Автор: Claude Opus 4.7.
