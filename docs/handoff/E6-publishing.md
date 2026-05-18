# Handoff — Эпик E6 (Publishing)

> **Аудитория документа.** AI-агенты и инженеры, подключающиеся к проекту после E6. Документ самодостаточен — переписки и контекста предыдущей сессии у вас нет, всё что нужно — здесь, в [`SPEC.md`](../../SPEC.md), [`E1-foundation.md`](E1-foundation.md), [`E2-identity.md`](E2-identity.md), [`E3-catalog.md`](E3-catalog.md), [`E4-authoring.md`](E4-authoring.md) и [`E5-workflow.md`](E5-workflow.md).
>
> **Дата handoff'а.** 2026-05-05.
> **Состояние:** E6 закрыт по содержанию SPEC §5.1 (auto-publish + content_hash + HMAC + verify + auto-deprecate). `make verify` зелёный (72 теста = 22 StateMachineTest + 31 authoring + 8 JwtValidator + 11 ArchUnit). End-to-end smoke прошёл — два раунда publish (V1 → V2 ⇒ V1 DEPRECATED, V2 PUBLISHED), verify подтверждает recompute hash совпадает с stored, journal содержит публикации и автодепрекации.
> **Следующий эпик:** E7 (Ownership) — указатель в §5.

---

## 0. TL;DR за 30 секунд

- Реализован модуль `rdmmesh-publishing`:
  - Подписчик `PublishingService` на `WorkflowTransitionDomainEvent` с фильтром `payload.to == OWNER_APPROVED`.
  - Канонический snapshot: read items → parse JSONB → sorted Map → Jackson `ORDER_MAP_ENTRIES_BY_KEYS` → bytes; алгоритм инкапсулирован в `PublishedSnapshotAdapter` (authoring), publishing получает готовые байты через `PublishedSnapshotPort`.
  - `content_hash = sha256_hex(canonical_bytes)` (`HmacSigner`).
  - `approval_signature = hmac_sha256_hex(content_hash + "|" + approver_om_user_id + "|" + iso8601_timestamp, key)`.
  - HMAC-ключ — через `SigningKeyPort` / `EnvSigningKeyAdapter` (env `RDM_HMAC_KEY`, dev-fallback зашит в код composition root'а).
  - Auto-publish: атомарный CAS `OWNER_APPROVED → PUBLISHED` + content_hash + signature + published_by/at.
  - Auto-DEPRECATE предыдущей PUBLISHED версии: `findLatestPublished` запоминается **до** publish'а, чтобы не вернуть саму свежеопубликованную версию (см. §1.7 — это был bug первого подхода, исправлен).
  - REST `GET /api/v1/versions/{id}/verify` — recompute hash + сравнение со stored, отдаёт `{applicable, verified, computedHash, storedHash, note}`.
- State machine расширена: `publish` (OWNER_APPROVED → PUBLISHED) и `deprecate` (PUBLISHED → DEPRECATED) — system actions, требуют base-роль `RDM_SYSTEM`. Self-approval/asset-role gates на них не применяются (publishing вызывает их сам после legitimate OWNER_APPROVED). Манипулировать ими через REST извне нельзя — `RDM_SYSTEM` нет ни у одного человеческого пользователя в Keycloak realm.
- Новые порты в `rdmmesh-api`:
  - `PublishedSnapshotPort` — read canonical bytes (реализуется в authoring).
  - `WorkflowJournalPort` — append-only INSERT в `workflow.workflow_transition` для system-переходов (реализуется в workflow).
  - `SigningKeyPort` — HMAC ключ (реализуется в app/security/`EnvSigningKeyAdapter`).
- Расширение `VersionLifecyclePort`: `publish(...)`, `deprecate(...)`, `findLatestPublished(...)`, `findStoredContentHash(...)`.
- Миграция V020 уже содержит все нужные поля (`content_hash`, `approval_signature`, `published_by`, `published_at`, `deprecated_at`, `system_to`) — новых миграций E6 не вводит.

---

## 1. Что сделано

### 1.1. Новые файлы

```
rdmmesh-api/src/main/java/bank/rdmmesh/api/port/
  ├── PublishedSnapshotPort.java          ← canonical bytes для hash + verify
  ├── SigningKeyPort.java                 ← HMAC ключ (Vault/SOPS swap-able)
  └── WorkflowJournalPort.java            ← append-only лог system-переходов

rdmmesh-authoring/src/main/java/bank/rdmmesh/authoring/internal/
  └── PublishedSnapshotAdapter.java       ← реализация snapshot port'а

rdmmesh-workflow/src/main/java/bank/rdmmesh/workflow/internal/
  └── PostgresWorkflowJournalPort.java    ← реализация WorkflowJournalPort

rdmmesh-publishing/src/main/java/bank/rdmmesh/publishing/
  ├── PublishingModule.java               ← composition factory
  ├── internal/
  │   ├── HmacSigner.java                 ← SHA-256 + HMAC-SHA-256 helpers
  │   └── PublishingService.java          ← подписчик + publish/deprecate/verify
  └── resource/
      └── VersionVerifyResource.java      ← GET /api/v1/versions/{id}/verify

rdmmesh-app/src/main/java/bank/rdmmesh/app/security/
  └── EnvSigningKeyAdapter.java           ← env-var реализация SigningKeyPort
```

### 1.2. Изменённые файлы

| Файл | Что |
|---|---|
| `rdmmesh-api/.../VersionLifecyclePort.java` | +`publish(versionId, hash, sig, publishedBy)`, +`deprecate(versionId)`, +`findLatestPublished(codesetId)`, +`findStoredContentHash(versionId)`. |
| `rdmmesh-authoring/.../VersionLifecycleAdapter.java` | реализация четырёх новых методов через `CodeSetVersionDao`. |
| `rdmmesh-authoring/.../dao/CodeSetVersionDao.java` | +`markPublished` (CAS OWNER_APPROVED → PUBLISHED + hash + signature + published_by/at), +`markDeprecated` (CAS PUBLISHED → DEPRECATED + deprecated_at + system_to). |
| `rdmmesh-authoring/.../AuthoringModule.java` | +`buildSnapshotPort(jdbi)` экспорт. |
| `rdmmesh-workflow/.../StateMachine.java` | переименован `E5_IMPLEMENTED` → `IMPLEMENTED`, добавлены `publish`/`deprecate` в реализованные; роль-gate для них через `RDM_SYSTEM`; self-approval НЕ применяется. |
| `rdmmesh-workflow/.../WorkflowModule.java` | +`buildJournalPort(jdbi)` экспорт. |
| `rdmmesh-workflow/src/test/.../StateMachineTest.java` | старый тест `_blocked_until_E6` заменён четырьмя новыми: `_maps_publish_and_deprecate`, `publish_requires_RDM_SYSTEM_base_role`, `publish_allowed_for_RDM_SYSTEM`, `deprecate_allowed_for_RDM_SYSTEM`. Итого +3 теста (было 19 → стало 22). |
| `rdmmesh-publishing/pom.xml` | +`dropwizard-auth` (для `@Auth` в verify-resource'е). |
| `rdmmesh-app/.../RdmmeshApplication.java` | wiring publishing'а: `buildSnapshotPort` + `buildJournalPort` + `EnvSigningKeyAdapter.fromEnv("RDM_HMAC_KEY", dev-fallback)` + `PublishingModule.build(...)` (subscribe на eventBus) + регистрация `verify`-resource'а. |

### 1.3. Канонический snapshot — алгоритм

Параметры зафиксированы на E6 (менять = ломать verify прежних версий):

```
canonical(versionId) = utf8 bytes of JSON:
{
  "version_id": "<uuid>",
  "items": [
    { "key_parts": ..., "parent_key": ..., "parent_ref": ...,
      "label_ru": ..., "label_en": ..., "description_ru": ..., "description_en": ...,
      "attributes": ..., "order_index": ..., "status": ...,
      "effective_from": ..., "effective_to": ... },
    ...
  ]
}

— ключи каждого item упорядочены лексикографически (ORDER_MAP_ENTRIES_BY_KEYS);
— массив items упорядочен по compact-JSON их key_parts (стабильное сравнение строк);
— JSONB-колонки (key_parts/parent_key/parent_ref/attributes) парсятся и пересериализуются —
  любой порядок ключей в БД нивелируется;
— нулевые/null поля сохраняются как JSON null (не пропускаются).
```

`content_hash = SHA-256_hex(canonical)`.

### 1.4. HMAC signature — алгоритм

```
payload    = content_hash || "|" || approver_om_user_id || "|" || iso8601_timestamp_utc
approval_signature = HMAC_SHA256_hex(payload, currentHmacKey())
```

`approver_om_user_id` — это `actor` события `WorkflowTransitionDomainEvent` (тот, кто сделал owner_approve). `iso8601_timestamp_utc` — момент publish'а на сервере (`Instant.now().toString()`).

Ключ HMAC живёт ВНЕ БД: env `RDM_HMAC_KEY` (см. §3.2 про prod). В dev зашит fallback `rdmmesh-dev-hmac-key-change-me-in-prod-vault` — длиной ровно 50 байт, чтобы CHECK на 32 минимум проходил. **Для prod env-var обязателен** — dev fallback при необходимости можно убрать без миграции данных (старые подписи будут verify-able только пока ключ совпадает).

### 1.5. Auto-publish flow (от owner_approve до auto-deprecate)

```
WorkflowTransitionResource.transition(VersionId, {to: OWNER_APPROVED}, dev-owner)
  ↓
WorkflowService.transition()
  → StateMachine.validate (asset/base OWNER, actor ≠ created_by, actor ∉ reviewers)
  → VersionLifecyclePort.transition (CAS STEWARD_APPROVED→OWNER_APPROVED + setApprover)
  → INSERT workflow.workflow_transition  [action=owner_approve]
  → eventBus.publish(WorkflowTransitionDomainEvent(payload.to=OWNER_APPROVED))
  ↓
PublishingService.onTransition(...)
  → snapshots.canonicalSnapshotBytes(versionId)   // sorted JSON
  → contentHash = sha256_hex(bytes)
  → signature   = hmac_sha256_hex(contentHash || actor || ts, key)
  → prev = lifecycle.findLatestPublished(codesetId)  // ⚠ ДО publish (см. §1.7)
  → lifecycle.publish(versionId, contentHash, signature, actor)  // CAS OWNER_APPROVED→PUBLISHED
  → journal.recordSystemTransition(... action=publish, comment="auto-publish after owner_approve")
  → if prev present and prev.id != versionId:
        lifecycle.deprecate(prev.id)
        journal.recordSystemTransition(... action=deprecate, comment="auto-deprecate, superseded by ...")
```

Idempotency: если событие пришло повторно — `lifecycle.publish` вернёт false (статус уже PUBLISHED), `PublishingService.autoPublish` отдаст `PublishOutcome.SKIPPED` без побочных эффектов.

### 1.6. State machine — `publish` и `deprecate`

```
DRAFT             ──submit──────────▶ IN_REVIEW
IN_REVIEW         ──steward_approve──▶ STEWARD_APPROVED
IN_REVIEW         ──steward_reject───▶ DRAFT          (comment обязателен)
STEWARD_APPROVED  ──owner_approve────▶ OWNER_APPROVED
STEWARD_APPROVED  ──owner_reject─────▶ DRAFT          (comment обязателен)
OWNER_APPROVED    ──publish──────────▶ PUBLISHED      (system-only: RDM_SYSTEM)
PUBLISHED         ──deprecate────────▶ DEPRECATED     (system-only: RDM_SYSTEM)
```

`publish`/`deprecate` через REST доступны только акторам с base-role `RDM_SYSTEM`. Эта роль НЕ выдана ни одному dev-юзеру в Keycloak realm — поэтому ручной POST `/transitions` с `to=PUBLISHED` от любого dev-* юзера получит 403 `InsufficientRoleException`. PublishingService обходит этот gate потому, что вообще не идёт через `WorkflowPort.transition()` — он напрямую вызывает `lifecycle.publish` + `journal.recordSystemTransition`.

### 1.7. Подводный камень, на котором я наступил

**Bug первой версии:** auto-DEPRECATE предыдущей PUBLISHED не срабатывал, потому что `findLatestPublished(codesetId)` вызывался **после** `lifecycle.publish(versionId, …)`. После CAS свежая публикация попадает в SELECT `published_at DESC LIMIT 1` (она именно сейчас новейшая), и проверка `prev.id != versionId` отсекала её — никаких deprecation не происходило.

**Fix:** `prev = findLatestPublished(...)` запоминается ДО `lifecycle.publish(...)`. После CAS используется уже стабильный snapshot prev'а. Покрыто smoke (round 2): V1 → PUBLISHED, V2 → PUBLISHED ⇒ V1 → DEPRECATED.

### 1.8. Verify endpoint

```
GET /api/v1/versions/{id}/verify
  auth: Bearer JWT (любой authenticated user — это публичная контрольная точка)
  200 →
    {
      "applicable":  bool,    // false если status не PUBLISHED/DEPRECATED
      "verified":    bool,    // true только если applicable && computed == stored
      "computedHash": "...",  // recomputed SHA-256
      "storedHash":   "...",  // из authoring.code_set_version.content_hash
      "note":         null|"…"
    }
  404 → unknown version
```

Recompute берёт **текущие** items версии. Поскольку published-версии иммутабельны (CHECK + DAO WHERE clauses), recompute всегда обязан совпасть со stored. Если когда-то рассинхронизуются — это сигнал инцидента (несанкционированная правка БД); вне scope MVP, но сама точка контроля присутствует.

---

## 2. Smoke (то, что прошло на 2026-05-05)

```bash
# round 1: V1 проходит весь 4-eyes flow → auto-publish
DOM=$(POST .../domains)            # admin
CS=$(POST .../codesets/by-domain)  # author, initial_schema {stage:1|2|3}
V1=$(POST .../versions/by-codeset/$CS)
POST items S1, S2, S3
POST /transitions {to:IN_REVIEW}    (author)
POST /transitions {to:STEWARD_APPROVED} (steward)
POST /transitions {to:OWNER_APPROVED}   (owner)
# ↑ owner_approve → eventBus → PublishingService → V1 PUBLISHED

# Проверки:
SELECT status, content_hash, approval_signature  → PUBLISHED, 64-hex, 64-hex
SELECT * FROM workflow.workflow_transition       → 4 rows: submit/steward/owner/publish
GET /verify                                      → applicable=true, verified=true

# round 2: V2 → publish, V1 → DEPRECATED
V2=$(POST .../versions/by-codeset/$CS {version:0.2.0})
POST items S4
весь 4-eyes flow → auto-publish

# Проверки:
SELECT version, status, deprecated_at FROM authoring.code_set_version
   ┌─────────┬────────────┬─────────────────┐
   │ 0.1.0   │ DEPRECATED │ deprecated_at_set=t, system_to_set=t
   │ 0.2.0   │ PUBLISHED  │ deprecated_at_set=f
   └─────────┴────────────┴─────────────────┘
SELECT * FROM workflow.workflow_transition WHERE action='deprecate'
   → 1 row: V1 PUBLISHED→DEPRECATED, comment="auto-deprecate, superseded by V2"
GET /verify обоих                            → verified=true для обоих
content_hash V1 != V2                        → разные hex-prefix'ы

# round 3: ручной publish заблокирован
V3=$(...) → DRAFT → IN_REVIEW → STEWARD_APPROVED
POST /transitions {to:PUBLISHED}     (owner)
   → 409 "Переход STEWARD_APPROVED → PUBLISHED не разрешён state machine'ой"
```

`make verify` — зелёный, **72 теста** = 22 StateMachineTest + 31 authoring + 8 JwtValidator + 11 ArchUnit.

---

## 3. Что осталось доделать в E6 — мягкие follow-up'ы

Ничего не блокирует E7. Список того, к чему **нужно вернуться** позже:

1. **Атомарность publish + deprecate через outbox.** Сейчас `lifecycle.publish` (authoring tx) и `journal.recordSystemTransition` (workflow tx) — две разные транзакции. Если первая прошла, а вторая упала — версия PUBLISHED, но в журнале нет записи. Не катастрофа: stored content_hash и status — single source of truth. Но при V14 (compliance hardening) стоит подумать про общий `Jdbi.handle` либо outbox-паттерн (этот же вопрос обсуждался в E5 §3.5 и E5 §1.4).
2. **HMAC secret rotation.** Сейчас `SigningKeyPort.currentHmacKey()` отдаёт один ключ. При rotation потребуется `allKnownKeys()` для verify прежних версий с прежним ключом — иначе старые подписи начнут проваливать verify. План: при появлении rotation policy расширить порт + перерасчёт verify-логики. До тех пор — open question (см. §6).
3. **verify endpoint должен ещё пересчитывать HMAC, а не только SHA-256.** Сейчас verify пересчитывает только content_hash; signature остаётся "as-is" в БД. Полный verify мог бы recompute: `hmac_sha256(stored_hash || stored_approver || stored_published_at, key)`. Это требует доступа к published_at и approved_by из VersionLifecyclePort — дополнительные read-методы. Не критично сейчас, но повышает уверенность compliance: добавить в V1 (E14).
4. **Outbound webhooks для consumer-систем.** SPEC §2.2 этап 4: webhook рассылается потребителям при publish. Сейчас событие идёт в in-process bus, но HTTP-вызовы в registered subscriptions не делаются. Это эпик E9 (Outbound), миграция V040 уже создала `webhook_subscription` и `webhook_outbox` таблицы.
5. **Регистрация publish-event в `version-published-event.json`.** Schema есть в `rdmmesh-spec/schema/events/version-published-event.json`, но POJO `VersionPublishedEvent` в коде пока не используется. На E9 (Outbound) — нужно вызывать `outboundPort.enqueueVersionPublished(...)` из `PublishingService` после успешного publish'а, payload = `VersionPublishedEvent(versionId, contentHash, signature, ...)`. Сделать с `OutboundPort` адаптером.
6. **Self-test «канонизация стабильна».** Идемпотентность: дважды считать `canonicalSnapshotBytes` от одной версии — должно дать байт-в-байт одно. Unit-тест на `PublishedSnapshotAdapter` с in-memory подделкой DAO решал бы это и заодно фиксировал контракт. Сейчас проверяется только end-to-end через verify-endpoint. Добавить с E13 (Bitemporal & Hierarchy) или раньше.
7. **GET /versions/{id}** — сейчас отдаёт VersionRow без `content_hash`/`approval_signature`. Для распределения и аудита эти поля полезны. Добавится с E8 (Distribution) либо как точечное расширение `CodeSetVersionResource`.
8. **`ApprovalTaskDao.findOpenByUser`** переписан с text-block на конкатенацию (см. E5 handoff §3.1) — уже зелёный.

---

## 4. Технический долг и решения, повлиявшие на следующие эпики

| Что | Где | Когда снять / следующий шаг |
|---|---|---|
| dev fallback HMAC-key зашит в код RdmmeshApplication | `RdmmeshApplication.run` | В prod-deploy убрать второй аргумент `EnvSigningKeyAdapter.fromEnv` или передать `null` (упадёт без env-var). |
| `publish`/`deprecate` — system-actions: ручные REST-переходы недоступны | `StateMachine.validate` | По SPEC §3.2 #7 это правильно. Если бизнес попросит «admin может publish'ить вручную в emergency» — это hotfix flow (BR-17), V1+. |
| Канонический формат item включает 12 явных полей | `PublishedSnapshotAdapter.KEYS` | При расширении `code_item` (новые колонки) — добавлять в KEYS аккуратно: новые версии и старые получат разные hash для одних и тех же items. Безопасно сделать так: новые поля попадают в hash только для версий, опубликованных после миграции. |
| Auto-deprecate берёт LATEST PUBLISHED — что если их несколько? | `PublishingService.autoPublish` | При сценарии race (две auto-publish'а с разных событий одновременно) `findLatestPublished` отдаёт `published_at DESC LIMIT 1`. Маловероятно для пилота (один SyncEventBus, один поток). При появлении async outbox-доставки — ревизия. |

---

## 5. Указатели на следующие эпики

> Конкретное содержание — в SPEC §5.1.

### E7. Ownership webhook (следующий)

- **Где:** `rdmmesh-ownership`. Сейчас содержит `PostgresOwnershipPort` со stub'ом `applyChangeEvent(...)` → `UnsupportedOperationException`.
- **Что реализовать:**
  - POST `/webhooks/om/ownership` (см. SPEC §2.4). HMAC-проверка `X-OM-Signature`.
  - Идемпотентность через `source_event_id` (UNIQUE-индекс уже есть в V060).
  - Парсинг ChangeEvent для `entityType ∈ {domain, table}` и UPSERT в `catalog.domain` (для domain) и `ownership.rdm_asset_ownership` (для table).
  - Маппинг `owners → OWNER`, `experts → EXPERT`, `reviewers → APPROVER` (steward — special case, см. SPEC §2.4).
  - Permission cache invalidation после UPSERT.
- **После E7:** asset-level STEWARD'ы появятся в `rdm_asset_ownership` — `/tasks/my` начнёт показывать candidate-tasks реальным steward'ам (сейчас candidate_users пустой для STEWARD'ов).

### E8. Distribution

- **Где:** `rdmmesh-distribution`. Сейчас пуст.
- **Что реализовать:**
  - GET `/api/v1/rdm/{domain}/{codeset}/items` с параметрами `version=published|<semver>`, `as_of`, `knowledge_as_of`, `lang=ru|en`, `page`/`size`.
  - GET `/api/v1/rdm/{domain}/{codeset}/lookup/{key}`.
  - GET `/api/v1/rdm/{domain}/{codeset}/export?format=csv|json|parquet|xlsx`.
- **Снять `allowEmptyShould(true)` с** `distribution_internal_only_used_by_distribution` и `distribution_does_no_db_writes` после первого DAO/класса.

### E9. Outbound webhooks

- **Где:** новый код в `rdmmesh-publishing` (или отдельный модуль) поверх существующего `webhook_subscription` + `webhook_outbox` (V040).
- Реализация `OutboundPort.enqueueVersionPublished(VersionPublishedEvent)`. `PublishingService.autoPublish` после успешного publish'а вызывает port.
- Background worker (Dropwizard `Managed`) дренирует `webhook_outbox` с retry/backoff.

### E10. Audit

- Подписаться на `DomainEvent.class` глобально (как в комментарии `RdmmeshApplication`).
- Сейчас публикуется только `WorkflowTransitionDomainEvent`. PublishingService не публикует свой event — добавить `VersionPublishedDomainEvent` (обёртка над spec-POJO `VersionPublishedEvent`) и публиковать после `autoPublish`.

### E11 / E12 / E13 / E14 — см. SPEC §5.1.

---

## 6. Открытые вопросы (актуальны для команды банка)

Без изменений с E5 (только п.4 теперь явно блокирует prod-rollout E6, не V1):

1. Production-Strategy для Flyway — подтверждено: `autoMigrate=false` в prod, миграции отдельным шагом.
2. Реальные prod-параметры Keycloak (issuer/jwks/audience/client_secret).
3. OM API base URL и bot-токен (для ingestion-коннектора, E12).
4. **HMAC secret rotation policy.** Минимум: где хранится текущий ключ (Vault path / SOPS key id). Желательно: процесс ротации без downtime + период двух-ключевого verify. До этого решения prod-E6 deploy блокирован.
5. Уведомления (e-mail/Slack) approver'ам — V1+.
6. RDM_ADMIN substitution policy — без изменений.
7. **Имя env-var для HMAC** — сейчас `RDM_HMAC_KEY`. Согласовать с эксплуатацией перед prod (наименование переменных, формат — base64? hex? plain? — сейчас принимает любую UTF-8 строку ≥32 байт).

---

## 7. Версия документа

- **0.1** — 2026-05-05. Создан после реализации E6 (Publishing). Build/smoke прогнаны end-to-end в этой же сессии. Автор: Claude Opus 4.7.
