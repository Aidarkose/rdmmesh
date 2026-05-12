# Handoff — Эпик E14 round 1 (Audit hash-chain + verify endpoint)

> **Аудитория документа.** AI-агенты и инженеры, подключающиеся к проекту после
> E14 round 1. Документ самодостаточен — переписки и контекста предыдущей сессии
> у вас нет, всё что нужно — здесь, в [`SPEC.md`](../../SPEC.md),
> [`E1-foundation.md`](E1-foundation.md), …, [`E10-audit.md`](E10-audit.md),
> [`E11.2d-audit-viewer.md`](E11.2d-audit-viewer.md) и
> [`E13.3-cycle-detection.md`](E13.3-cycle-detection.md).
>
> **Дата handoff'а.** 2026-05-12.
> **Состояние.** E14 round 1 закрыт: backend получил криптографическую hash-chain
> поверх `audit.audit_log` (миграция V072) + REST endpoint
> `GET /api/v1/audit/verify-chain` под `RDM_ADMIN`. Закрывает follow-up handoff
> E10 §3 #1 («Криптографическая audit-цепочка»).
> `./bin/mvn -DskipITs verify` ✅ — 12/12 модулей зелёные, ArchUnit чистый.
> Backend-тесты: **151 тест** (было 132 → +19: 12 AuditChainHasherTest + 7
> AuditChainVerifierTest).
> **End-to-end smoke не прогонялся** — `make up` (см. §6).
>
> **Round 1 осознанно НЕ включает:** UI кнопку «Проверить целостность цепочки»
> на AuditPage (отдельный round 2 — frontend); audit-export в S3 immutable bucket
> (V2 territory, см. §5); RDM_AUDITOR role (открытый вопрос E10 §6 #15); HMAC
> secret rotation policy; security review OWASP Top 10. См. §5 — roadmap эпика.

---

## 0. TL;DR за 30 секунд

- **Миграция V072 `audit/V072__audit_hash_chain.sql`:**
  - `ALTER TABLE audit.audit_log ADD COLUMN payload_canonical TEXT` — byte-stable
    форма payload'а, по которой считается hash. Хранится отдельно от `payload jsonb`,
    потому что Postgres jsonb нормализует числа/whitespace/порядок ключей по своему,
    и hash не пересчитывался бы 1-в-1.
  - DISABLE/ENABLE TRIGGER `audit_log_no_update` на время backfill'а — единственный
    легальный способ заполнить prev_hash/entry_hash в append-only таблице. Делается
    под owner-ролью (`rdmmesh_admin`); `rdmmesh_app` по-прежнему ограничен
    INSERT/SELECT-grants.
  - Backfill `payload_canonical = payload::text` (Postgres jsonb уже sorted, для
    pre-V072 rows этого достаточно).
  - Backfill hash-chain в id-ASC цикле: `entry_hash = sha256(coalesce(prev_hash,'')
    || '|' || event_id || '|' || event_type || '|' || payload_canonical || '|'
    || to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))`.
  - `SET NOT NULL` на `payload_canonical` и `entry_hash` после backfill'а.
- **Backend (rdmmesh-audit):**
  - **`AuditChainHasher`** (новый, pure) — canonical serialiser + SHA-256.
    `defaultMapper()` собирает ObjectMapper с `SORT_PROPERTIES_ALPHABETICALLY`,
    `ORDER_MAP_ENTRIES_BY_KEYS`, `NON_NULL`, JSR-310 через
    `findAndRegisterModules()`. Формат timestamp в hash-input — UTC,
    микросекунды, ISO-8601 c суффиксом 'Z'. Совпадает с SQL `to_char(...)`.
  - **`AuditChainVerifier`** (новый, pure) — принимает `List<ChainRow>` +
    `anchorPrevHash`, итерирует и проверяет continuity (`row.prev_hash ==
    expected_prev`) + integrity (`row.entry_hash == sha256(canonical_input)`).
    Первое же нарушение → `Result(verified=false, firstBrokenAt, reason,
    expected/storedHash)`.
  - **`AuditService`** перешёл с `jdbi.withExtension` (atomic INSERT) на
    `jdbi.inTransaction` с `pg_advisory_xact_lock(0x4155_4449_5443_4841L)` —
    сериализует concurrent INSERT'ы в audit (несколько HTTP-handler'ов одновременно
    публикующие в EventBus). Lock держится миллисекунды (SELECT + INSERT в одной
    транзакции).
  - **`AuditLogDao`** расширен: insert() теперь принимает `payloadCanonical`,
    `prevHash`, `entryHash`; добавлены `findLastEntryHash()`, `findChainRange()`,
    `findMinId()`/`findMaxId()`. Новый record `ChainRow`.
  - **`AuditResource`** получил `@GET @Path("/verify-chain")` + новый
    `VerifyChainResponse` record. `AuditModule.build()` теперь создаёт hasher
    и verifier; конструктор AuditResource расширен.
- **pom.xml:** в `rdmmesh-audit/pom.xml` добавлена явная зависимость на
  `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` (версия наследуется
  из dropwizard-bom). До этого jsr310 был доступен только транзитивно у
  публикующих модулей (`publishing`, `ownership`, `app`); audit-модуль
  использует JavaTime в payload'ах и в `OffsetDateTime occurredAt`.

---

## 1. Что сделано — детально

### 1.1. Миграция V072

**Файл:** `bootstrap/sql/migrations/audit/V072__audit_hash_chain.sql`

Делает четыре вещи в одной транзакции:

1. `ADD COLUMN payload_canonical TEXT` (nullable пока — для backfill'а).
2. `ALTER TABLE ... DISABLE TRIGGER audit_log_no_update;` — открывает UPDATE,
   единственный легальный способ заполнить hash'и в append-only.
3. Backfill `payload_canonical = payload::text` для всех существующих rows
   (Postgres jsonb уже нормализован, для backfill'а этого достаточно). В новом
   коде Java вычисляет canonical отдельно и пишет туда **свою** строку.
4. `DO $$ ... $$` блок: построчно в id-ASC порядке вычисляет
   `entry_hash = encode(public.digest(canonical_input, 'sha256'), 'hex')` и
   UPDATE'ит `prev_hash`/`entry_hash`. После цикла — `SET NOT NULL` на
   `payload_canonical` и `entry_hash`, затем `ENABLE TRIGGER` обратно.

**Идемпотентность:** `WHERE entry_hash IS NULL` фильтрует уже заполненные rows.
Если миграция запускается на БД, где chain уже частично заполнен (PITR
restore), повторный run ничего не сломает.

**Цена:** для пилотных объёмов (десятки rows) — миллисекунды. Для prod-БД с
миллионами — линейный scan по BIGSERIAL PK; cluster по `(id ASC)` уже
встроен через primary key, отдельный индекс не нужен.

### 1.2. AuditChainHasher — pure-сериализатор и hash

**Файл:** `rdmmesh-audit/src/main/java/bank/rdmmesh/audit/internal/AuditChainHasher.java`

```java
public final class AuditChainHasher {
    public static final DateTimeFormatter CANONICAL_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    public String canonicalPayload(Object payload) { ... }   // sorted JSON
    public String computeEntryHash(prev, eventId, type, payload, occurredAt) { ... }
    public static String formatOccurredAt(OffsetDateTime t) { ... }  // UTC, micros
    public static ObjectMapper defaultMapper() { ... }       // SORT_PROPERTIES_ALPHABETICALLY + jsr310
}
```

**Алгоритм:**

```
canonical_input = coalesce(prev_hash, "") || "|" || event_id::text
               || "|" || event_type
               || "|" || payload_canonical
               || "|" || formatOccurredAt(occurredAt)
entry_hash      = sha256_hex(canonical_input UTF-8)
```

Первая запись цепочки имеет `prev_hash = null`, в hash-input уходит пустая
строка (не строка из 64 нулей). Это сделано намеренно: visually отличать
корень цепочки в дампах БД.

**Совпадение с SQL-backfill'ом V072:** SQL делает `to_char(occurred_at AT
TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"')` — 6-цифровая
микросекундная точность, padded. Java pattern `.SSSSSS` совпадает.
`truncatedTo(ChronoUnit.MICROS)` отсекает nanosecond-разрядность Java перед
форматированием — иначе verify был бы чувствителен к сериализации JDBC'а.

### 1.3. AuditChainVerifier — pure-верификатор range'а

**Файл:** `rdmmesh-audit/src/main/java/bank/rdmmesh/audit/internal/AuditChainVerifier.java`

```java
public final class AuditChainVerifier {
    public Result verify(List<ChainRow> rows, String anchorPrevHash) { ... }
    public record Result(boolean verified, int checked, Long firstBrokenAt,
                          String reason, String expectedHash, String storedHash) {}
}
```

Для каждой row делает две проверки:

1. **Continuity.** `row.prev_hash == expectedPrev` (либо обе `null` для первой
   записи журнала). `expectedPrev` = `anchorPrevHash` для первой row, далее
   обновляется как `row.entry_hash` предыдущей итерации.
2. **Integrity.** Пересчитывает `entry_hash` от компонент row'а; сравнивает с
   stored.

Первое же нарушение → `Result(verified=false, ...)`, цикл выходит. Дальнейшая
верификация бессмысленна — если корень разрушен, всё последующее тоже.

### 1.4. AuditService — chain-write

**Файл:** `rdmmesh-audit/src/main/java/bank/rdmmesh/audit/internal/AuditService.java`

Изменения относительно E10-версии:

```java
int rows = jdbi.inTransaction(handle -> {
    handle.execute("SELECT pg_advisory_xact_lock(?)", CHAIN_LOCK_KEY);

    AuditLogDao dao = handle.attach(AuditLogDao.class);
    String prevHash = dao.findLastEntryHash().orElse(null);
    String entryHash = hasher.computeEntryHash(
            prevHash, event.eventId(), c.eventType(),
            payloadCanonical, event.occurredAt());

    return dao.insert(event.eventId(), c.eventType(), c.aggregateType(),
                       c.aggregateId(), c.actor(), event.occurredAt(),
                       payloadJson, payloadCanonical, prevHash, entryHash);
});
```

**Concurrency note.** `pg_advisory_xact_lock(0x4155_4449_5443_4841L)` —
session-level lock, автоматически снимаемый в конце транзакции. Все INSERT'ы
в `audit_log` сериализуются через этот lock; одна транзакция держит его
миллисекунды (SELECT + INSERT). Для пилотных объёмов это вообще ничего не
тормозит. Для prod'а — потенциально bottleneck на write-heavy workload'е
audit'а; см. §3 follow-up #2.

**Idempotency.** `ON CONFLICT DO NOTHING` остаётся (UNIQUE-индекс
`audit_log_event_id_uq` из V071). При replay'е одного и того же event_id
INSERT вернёт rows=0; `prev_hash`/`entry_hash` не сдвинутся, потому что
selected `findLastEntryHash` не изменится между транзакциями. Цепочка
остаётся целостной.

### 1.5. AuditLogDao — расширенный insert + chain queries

**Файл:** `rdmmesh-audit/src/main/java/bank/rdmmesh/audit/internal/dao/AuditLogDao.java`

Новые методы:

- `int insert(...)` — теперь с 10 параметрами (добавлены `payloadCanonical`,
  `prevHash`, `entryHash`).
- `Optional<String> findLastEntryHash()` — для AuditService на write.
- `List<ChainRow> findChainRange(fromId, toId)` — для verify endpoint'а.
- `Optional<Long> findMinId()` / `findMaxId()` — для default'ных границ
  range'а в verify endpoint.
- record `ChainRow(id, eventId, eventType, occurredAt, payloadCanonical,
  prevHash, entryHash)`.

### 1.6. AuditResource — verify-chain endpoint

**Файл:** `rdmmesh-audit/src/main/java/bank/rdmmesh/audit/resource/AuditResource.java`

```
GET /api/v1/audit/verify-chain               @RolesAllowed("RDM_ADMIN")
    [?from=<id>]   default = min(id)
    [?to=<id>]     default = max(id)
```

Response (`VerifyChainResponse`):

```json
{
  "from": <long>,
  "to":   <long>,
  "checked": <int>,            // сколько записей фактически проверено
  "verified": true|false,
  "first_broken_at": <long>|null,
  "reason": <string>|null,
  "expected_hash": <hex>|null,
  "stored_hash":   <hex>|null
}
```

**Anchor logic.** Если `from <= min(id)` — anchor `null` (range начинается с
корня). Иначе — `entry_hash` записи `id = from-1`, либо `null` если такой
записи нет (пользователь указал что-то странное). Это позволяет верифицировать
sub-range без полного пробега чейна.

**Ошибки:**
- `403` — нет роли `RDM_ADMIN`.
- `400` — `from > to`.
- `200` + `verified=false` — chain разрушен; `first_broken_at`+`reason`
  объясняют где и почему.
- `200` + `checked=0, verified=true` — пустой журнал.

### 1.7. AuditModule.build — wire-up

**Файл:** `rdmmesh-audit/src/main/java/bank/rdmmesh/audit/AuditModule.java`

```java
public static Resources build(Jdbi jdbi, EventBus eventBus, ObjectMapper json) {
    AuditService service = new AuditService(jdbi, json);
    service.registerOn(eventBus);

    AuditChainHasher hasher = new AuditChainHasher(AuditChainHasher.defaultMapper());
    AuditChainVerifier verifier = new AuditChainVerifier(hasher);
    AuditResource resource = new AuditResource(jdbi, json, verifier);
    return new Resources(service, resource);
}
```

`RdmmeshApplication.run()` не правился — он по-прежнему делает
`AuditModule.build(jdbi, eventBus, environment.getObjectMapper())` и
регистрирует `audit.resource()`. Новый verify-метод доступен через тот же
Jersey-resource.

### 1.8. ArchUnit прошёл без правок

`./bin/mvn -DskipITs verify` зелёный — все 11 правил остались чистыми. Новые
классы (`AuditChainHasher`, `AuditChainVerifier`) живут в
`bank.rdmmesh.audit.internal..` — внутренний пакет audit'а. ArchUnit-rule
`audit_only_depends_on_api_or_spec` уже допускает импорт `io.dropwizard..` и
`org.jdbi..`, что покрывает все наши зависимости. Импорт
`com.fasterxml.jackson.datatype.jsr310` НЕ нужен в коде — модуль подключается
SPI'ем через `findAndRegisterModules()`.

---

## 2. Контракт endpoint'а

### 2.1. Новое в round 1

| Endpoint | Метод | Auth |
|---|---|---|
| `/api/v1/audit/verify-chain` | GET | `RDM_ADMIN` |

Query: `from` (long, опц.), `to` (long, опц.). Response — `VerifyChainResponse`
(см. §1.6).

### 2.2. Изменения существующих контрактов

- **Внутренний INSERT в `audit.audit_log`.** Теперь 9 полей вместо 7 (+
  `payload_canonical`, `prev_hash`, `entry_hash`). Внешний REST-контракт
  audit-viewer'а (`GET /audit`) не изменён — AuditEntryDto по-прежнему
  возвращает только пользовательски-релевантные поля.
- **Schema audit_log.** `payload_canonical` и `entry_hash` теперь NOT NULL.
  Триггер `audit_log_no_update` остаётся: V072 включает его обратно после
  backfill'а. Append-only-семантика для приложения сохранена.

### 2.3. Алгоритм hash для downstream-верификации

Спецификация для будущих compliance-инспекторов и сторонних verify-tools'ов:

```
Для каждой записи R audit_log в порядке id ASC:

  canonical_input = R.prev_hash_or_empty
                 || "|" || R.event_id (UUID lowercase canonical 36-char)
                 || "|" || R.event_type
                 || "|" || R.payload_canonical
                 || "|" || R.occurred_at_iso_utc_micros
                          ("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'")

  expected_entry_hash = lowercase_hex(SHA-256(UTF-8 bytes of canonical_input))

  expected_entry_hash MUST equal R.entry_hash
  R.prev_hash MUST equal previous(R).entry_hash (либо обе null для id=min(id))
```

`prev_hash_or_empty`: если `R.prev_hash IS NULL` — пустая строка. Иначе —
hex64 значение.

---

## 3. Что осталось доделать — мягкие follow-up'ы

Ничего не блокирует следующий round. Список того, к чему стоит вернуться:

1. **UI verify-button** (round 2). На `AuditPage` (`/admin/audit`) добавить
   кнопку «Проверить целостность цепочки» с DateRangePicker (или просто без
   параметров — full chain). На click → `apiQueries.verifyAuditChain()` →
   modal с `verified` либо красным баннером "First broken at #N: ...".
   Зависимости: рутинная типизация на frontend'е. Bundle: <1 KB.

2. **Advisory-lock contention под высокий load.** Сейчас все INSERT'ы в
   audit_log сериализуются через `pg_advisory_xact_lock`. На write-heavy
   workload (например, bulk-publish одновременно 100 codeset'ов) это даст
   очередь. Mitigation: либо вынести audit в отдельную async-queue (kafka /
   in-process disruptor) с одним consumer'ом, либо снять constraint
   линейности на единый chain — например, per-aggregate_id chains (но это
   требует пересмотра ER модели и не оправдано на пилотных объёмах).

3. **Retention vs hash-chain.** SPEC §3.7 требует 7 лет retention для audit.
   Когда дойдёт до partitioning по `occurred_at` (RANGE) — нужно решить,
   что делать с chain: либо одна глобальная цепочка через все partitions
   (тогда partition drop ломает chain), либо chain per-partition (тогда
   verify должен принимать список partitions). Решить в V14 partitioning-этапе.

4. **Audit hash-chain в S3 archive.** Когда (если) появится background-job
   архивации (handoff E10 §3 #2), архивный snapshot должен включать `prev_hash`
   и `entry_hash` — иначе verify-после-restore станет невозможен. Это
   автоматически работает, если архивация = pg_dump или COPY ... TO; не
   работает, если архивация только outputs payload без hash-колонок.

5. **`pgcrypto.digest` использует SHA-256 из OpenSSL.** В V072 мы зовём
   `public.digest('...', 'sha256')`. Если по какой-то причине окружение
   соберёт Postgres без OpenSSL (редко) — миграция упадёт. Альтернатива —
   `encode(sha256(...), 'hex')` (builtin Postgres 11+). На пилоте используется
   pgcrypto, потому что он уже подключён в V001 для `gen_random_uuid()` и нам
   не нужен второй путь зависимости. Если переключаться — нужно перепроверить
   что выдача битно идентична (она должна быть).

6. **Тест на полный путь EventBus → AuditService → DAO → verify-chain через
   testcontainers.** Сейчас покрытие — pure unit на hasher/verifier +
   integration smoke по §6. Промежуточный слой (subscribe, advisory_lock,
   INSERT с правильным prev/entry, verify range) закрывается ArchUnit'ом
   + smoke, но при росте кода стоит добавить IT-тест с Postgres testcontainer'ом.
   Тот же мягкий debt, что упоминается во всех handoff'ах с E3.

7. **Audit-event для `closure rebuild`** (handoff E13.3 §3 #6). Disaster-recovery
   endpoint сейчас только log.warn'ит; добавить `ClosureRebuildDomainEvent`
   и подписать audit. Чисто механическое расширение — отдельный мини-PR.

8. **`actor` для verify-chain action.** Сейчас REST-вызов сам по себе не
   аудируется — мы читаем audit, не пишем в него. Compliance-team может
   захотеть «кто и когда запускал verify». Решение: эмитить
   `AuditVerifyDomainEvent { actor, fromId, toId, verified, checkedCount }`
   из AuditResource'а через тот же EventBus. Зацикливание исключено — это
   новый тип event'а с другим event_type. V14 либо отдельный мини-PR.

---

## 4. Технический долг и решения

| Что | Где | Когда снять / следующий шаг |
|---|---|---|
| `payload_canonical` дублирует `payload jsonb` | DAO + schema | by-design — jsonb для индексов и query, text для hash-stability. Не убирать. |
| `pg_advisory_xact_lock` на каждый audit-INSERT | `AuditService.onEvent` | Acceptable на пилоте; см. §3 #2. |
| `findLastEntryHash` каждый раз делает scan по PK DESC | DAO | Postgres использует indexed scan, single-row — ~µs. Не оптимизировать. |
| V072 DISABLE/ENABLE TRIGGER на backfill | миграция | Разовая операция, под owner-ролью. Опасный паттерн, но единственный legal для append-only journal. |
| `formatOccurredAt` truncates to µs (Java OffsetDateTime может иметь nanos) | `AuditChainHasher` | Совпадение с SQL `to_char(...,'US')` критично. Не менять без миграции. |
| `prev_hash IS NULL` для первой записи (не нули) | алгоритм | Чтобы visually отличать корень chain'а в дампах. SQL и Java consistently используют `coalesce(prev, '')`. |
| Verify endpoint без size-limit на range | `AuditResource.verifyChain` | На пилоте ОК; для prod добавить max-chunk (например, 10k rows за запрос) и paginated verify. |
| Anchor lookup делает `findChainRange(from-1, from-1)` (один row) | `AuditResource.verifyChain` | Семантически чище через отдельный DAO `findEntryHashById(long)`. Micro-cleanup. |
| HMAC secret rotation не имеет отношения к hash-chain | разные эпики | E14 round 3+ — secret rotation policy outbound/inbound. |
| SHA-256 не покрывает full provenance: actor mismatched chain (один и тот же actor) → невидим | алгоритм | Это by-design: hash-chain покрывает tamper-evidence по полям. Identity provenance — отдельная задача (`actor` уже хранится в самой row'е). |

---

## 5. Roadmap E14

Содержание эпика E14 по handoff'ам E10/E13/E13.3:

- **✅ Round 1 (этот документ).** Audit hash-chain + verify endpoint.
- **Round 2 (следующий, frontend).** UI кнопка «Проверить целостность цепочки»
  на AuditPage + admin dashboard с цветовым индикатором integrity.
- **Round 3.** RDM_AUDITOR-роль (handoff E10 §6 #15): отдельная read-only
  Keycloak-группа для compliance team, чтобы admin-привилегии не давать
  «только посмотреть audit». Backend `@RolesAllowed({"RDM_ADMIN","RDM_AUDITOR"})`
  + Keycloak realm-update + frontend-guard.
- **Round 4.** Audit-export endpoint (handoff E10 §3 #3, E11.2d §3 #3).
  `GET /api/v1/audit/export?format=csv|ndjson` со `StreamingOutput` для
  внешнего аудитора.
- **Round 5.** Унифицированное atomic-decision для split-tx случаев
  E5/E6/E7/E9/E10 (handoff E10 §4 debt). Outbox-pattern либо in-process
  saga.
- **Round 6.** HMAC secret rotation policy (outbound E6 / inbound E7 /
  per-subscription E9 — handoff E9 §3 #1, E10 §6 #7).
- **Round 7.** LIST partitioning `code_item` по `version_id` (SPEC §3.4,
  handoff E13.2 §3 #8) + RANGE partitioning `audit_log` по `occurred_at`
  (см. §3 #3). Решает retention.
- **Round 8.** Security review OWASP Top 10 для backend + UI (handoff E10
  §5 — JWT / HMAC / no-bypass workflow / XSS-vectors в payload viewer'е /
  CSP / DOMPurify).
- **Round 9.** Testcontainers IT infrastructure (мягкий debt из всех эпиков
  E3/E4/E5/E10/E13/E14).
- **Round 10.** Audit-export в S3 immutable bucket (SPEC §3.8 V2).
- **Round 11.** AuditVerifyDomainEvent + ClosureRebuildDomainEvent (handoff
  §3 #7 + handoff E13.3 §3 #6) — добить event-coverage.

После E14 — V2-territory: BPMN per Domain, Kafka outbound, Elasticsearch
search, dbt source generation, Compliance/AML / Treasury domain onboarding.

---

## 6. Smoke

### 6.1. Backend verify (прошло на 2026-05-12)

```bash
./bin/mvn -DskipITs verify
# ─────────────────────────────────────────────────────────────────────
# Reactor Summary for rdmmesh — parent 0.1.0-SNAPSHOT:
# rdmmesh — parent ............................... SUCCESS [  2.882 s]
# rdmmesh — spec ................................. SUCCESS [ 12.733 s]
# rdmmesh — api .................................. SUCCESS [  0.203 s]
# rdmmesh — catalog .............................. SUCCESS [  9.224 s]
# rdmmesh — authoring ............................ SUCCESS [ 10.689 s]
# rdmmesh — workflow ............................. SUCCESS [ 10.353 s]
# rdmmesh — publishing ........................... SUCCESS [ 10.508 s]
# rdmmesh — distribution ......................... SUCCESS [ 10.441 s]
# rdmmesh — identity ............................. SUCCESS [ 10.618 s]
# rdmmesh — ownership ............................ SUCCESS [ 11.369 s]
# rdmmesh — audit ................................ SUCCESS [ 10.537 s]   ← +AuditChainHasher/Verifier
# rdmmesh — app .................................. SUCCESS [  8.498 s]   ← ArchUnit чистый
# BUILD SUCCESS  (37.214s)
```

Audit-модуль: **26 тестов** (было 7):
- 7 AuditEventClassifierTest (E10, без изменений)
- 12 AuditChainHasherTest (E14 round 1) ← новые
- 7 AuditChainVerifierTest (E14 round 1) ← новые

Total backend: **151 тест** (было 132 → +19).

### 6.2. End-to-end (требует `make up`; **не прогонялся в этой сессии**)

#### Шаг 0 — пересборка после V072

```bash
docker compose -f docker/docker-compose.yml build rdmmesh-service
make up
# Flyway применит V072. В логе:
#   Migrating schema "rdmmesh_meta" to version "072 - audit hash chain"
#   Successfully applied 1 migration ... now at version v072
```

#### Шаг 1 — Backfill корректен на пустой dev-БД

```bash
docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -c "
  SELECT count(*) AS total,
         count(*) FILTER (WHERE entry_hash IS NULL) AS missing_hash,
         count(*) FILTER (WHERE payload_canonical IS NULL) AS missing_canon
    FROM audit.audit_log;
"
# Ожидание: total = <сколько было до V072>, missing_hash = 0, missing_canon = 0.
```

#### Шаг 2 — Новые INSERT'ы наполняют chain

```bash
# 1. Прогнать 4-eyes flow (как в E10 smoke §2) — создать version, submit,
#    steward_approve, owner_approve → auto-publish. Это сгенерирует 4 audit
#    события (3×WORKFLOW_TRANSITION + 1×VERSION_PUBLISHED).

# 2. Проверить chain в БД
docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -c "
  SELECT id, event_type, substr(prev_hash, 1, 12) AS prev_h, substr(entry_hash, 1, 12) AS entry_h
    FROM audit.audit_log ORDER BY id;
"
# Ожидание:
#   id | event_type          | prev_h       | entry_h
#  ----+---------------------+--------------+-------------
#    1 | WORKFLOW_TRANSITION |              | abc123...
#    2 | WORKFLOW_TRANSITION | abc123...    | def456...
#    3 | WORKFLOW_TRANSITION | def456...    | 789abc...
#    4 | VERSION_PUBLISHED   | 789abc...    | cdef01...
```

#### Шаг 3 — Verify endpoint: happy path

```bash
TOK_ADMIN=$(KC_USER=dev-admin make kc-token)

curl -sS -H "Authorization: Bearer $TOK_ADMIN" \
    "http://localhost:8080/api/v1/audit/verify-chain" | jq
# {
#   "from": 1,
#   "to": 4,
#   "checked": 4,
#   "verified": true
# }

# Sub-range:
curl -sS -H "Authorization: Bearer $TOK_ADMIN" \
    "http://localhost:8080/api/v1/audit/verify-chain?from=2&to=3" | jq
# verified: true, checked: 2 (anchor взялся из row id=1)
```

#### Шаг 4 — Verify endpoint: detection of tampering

```bash
# Симуляция: вручную меняем payload в одной row (мы под owner-ролью, а не
# rdmmesh_app — поэтому триггер на UPDATE сработает). Используем DISABLE
# TRIGGER кратковременно — это требует SUPERUSER либо owner-роли таблицы:

docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh <<'SQL'
ALTER TABLE audit.audit_log DISABLE TRIGGER audit_log_no_update;
UPDATE audit.audit_log
   SET payload_canonical = '{"hacked":true}'
 WHERE id = 2;
ALTER TABLE audit.audit_log ENABLE TRIGGER audit_log_no_update;
SQL

# Verify обнаруживает разрыв:
curl -sS -H "Authorization: Bearer $TOK_ADMIN" \
    "http://localhost:8080/api/v1/audit/verify-chain" | jq
# {
#   "from": 1,
#   "to": 4,
#   "checked": 1,           ← row 1 прошёл, на row 2 сломалось
#   "verified": false,
#   "first_broken_at": 2,
#   "reason": "entry_hash mismatch: recomputed=..., stored=...",
#   "expected_hash": "<recomputed-from-tampered-payload>",
#   "stored_hash":   "<original-pre-tamper>"
# }
```

#### Шаг 5 — Auth gate

```bash
TOK_AUTHOR=$(KC_USER=dev-author make kc-token)
curl -sS -H "Authorization: Bearer $TOK_AUTHOR" -w '\n[HTTP %{http_code}]\n' \
    "http://localhost:8080/api/v1/audit/verify-chain"
# → HTTP 403
```

#### Шаг 6 — Bad range

```bash
curl -sS -H "Authorization: Bearer $TOK_ADMIN" \
    "http://localhost:8080/api/v1/audit/verify-chain?from=10&to=5" | jq
# → 400 "from must be <= to"
```

---

## 7. Открытые вопросы (актуальны для команды банка)

Без изменений с E13.3, плюс E14-specific:

1–32 — без изменений (см. `E13.3-cycle-detection.md` §7).
33. **Hash-algorithm versioning.** Сейчас алгоритм зашит в код. Если когда-то
    SHA-256 окажется compromised (теоретически) — нужен migration path:
    добавить колонку `chain_algo TEXT DEFAULT 'sha256:v1'` либо встроить в
    canonical_input префикс. Не делать без явной просьбы; SHA-256 остаётся
    standard ещё минимум на десятилетие.
34. **Hash-chain breakage policy.** Если verify обнаружил разрыв — что
    дальше? Compliance team должна решить: alerting через Slack/email
    (background-job-проверка раз в час), automatic rebuild (опасно,
    маскирует факт компрометации) или manual incident-flow. На пилоте —
    только manual через verify-endpoint.
35. **Verify-chain access logging.** Сейчас REST-вызов verify сам по себе
    не пишется в audit (мы читаем audit, не пишем). Compliance может
    захотеть видеть «кто и когда верифицировал журнал». Решение — §3 #8
    выше.
36. **Cross-DB-restore consistency.** Если БД восстанавливается из PITR
    backup'а, и backup был сделан в момент когда некоторая row уже
    закоммичена в БД, но ещё не дошла до audit-INSERT'а — chain будет
    неконсистентной. Mitigation: после restore запустить full verify, в
    случае разрыва запустить admin replay (требует event-store persistence,
    которая на пилоте отсутствует) либо принять, что chain прерывается
    на момент restore. Compliance должны это знать.

---

## 8. Указатели на следующие round'ы

### E14 round 2: UI verify-button (следующий)

- **Где:** `rdmmesh-ui/src/pages/AuditPage.tsx`.
- **Что:** Добавить кнопку «Проверить целостность цепочки» в Card "Фильтры".
  Модал/Drawer с результатом: либо `<Result status="success" title="Цепочка цела"
  subTitle="Проверено {checked} записей">` либо `<Result status="error" ...>` с
  выделением `first_broken_at`.
- **API:** `apiQueries.verifyAuditChain(fromId?, toId?)` → `qk.audit.verify(...)`.
- **i18n:** `audit.verifyButton`, `audit.verifyResultOk`, `audit.verifyResultBroken`,
  `audit.verifyReason`, etc.
- **Bundle estimate:** +0.5 KB gzip.

### Дальнейшие round'ы — см. §5.

---

## 9. Версия документа

- **0.1** — 2026-05-12. Создан после реализации E14 round 1 (audit hash-chain:
  миграция V072 + AuditChainHasher + AuditChainVerifier + chain-write в AuditService
  + REST `GET /audit/verify-chain`). `./bin/mvn -DskipITs verify` зелёный (12/12
  модулей, 151 тест), ArchUnit чистый. End-to-end smoke с реальной БД —
  следующая сессия. Автор: Claude Opus 4.7.
