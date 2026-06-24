# Handoff — Эпик E5 (Workflow)

> **Аудитория документа.** AI-агенты и инженеры, подключающиеся к проекту после E5. Документ самодостаточен — переписки и контекста предыдущей сессии у вас нет, всё что нужно — здесь, в [`SPEC.md`](../../SPEC.md), [`E1-foundation.md`](E1-foundation.md), [`E2-identity.md`](E2-identity.md), [`E3-catalog.md`](E3-catalog.md) и [`E4-authoring.md`](E4-authoring.md).
>
> **Дата handoff'а.** 2026-05-05; обновлено 2026-05-05 после прогона build + smoke.
> **Состояние:** E5 закрыт по содержанию SPEC §5.1 — за исключением переходов `publish` / `deprecate`, которые относятся к E6 (требуют snapshot + content_hash + HMAC). `make verify` зелёный (69 тестов = 19 StateMachineTest + 31 authoring + 8 JwtValidator + 11 ArchUnit). End-to-end smoke 4-eyes flow прошёл по §3.2 — все 14 шагов (submit/self-approval×2/steward_approve/owner_approve/publish-blocked/at-most-one-DRAFT/journal/reviewer/approved_by). Подробнее — §3.1 ниже.
> **Следующий эпик:** E6 (Publishing). Указатели — в §5.
>
> **⚠ Forward-pointer (2026-05-19, не переписывает историю E5).** Введена
> **адресная маршрутизация согласования** (SPEC §2.2/§2.4, BR-21/BR-22, эпик
> [`E17-approver-routing.md`](E17-approver-routing.md)): переход `submit`
> теперь принимает `assignee = {domain_id, role, om_user_id}`, валидирует тройку
> по новому справочнику `ownership.domain_role_directory` через новый порт
> `ApproverDirectoryPort`, и создаёт **адресную** `approval_task`
> (`candidate_users=[assignee]`, `assigned_role`) вместо broadcast'а всем
> asset-стюардам. Self-approval-проверка сохраняется (теперь и на `assignee`).
> Описанная ниже E5-матрица переходов и `/tasks/my`-фильтр остаются в силе —
> меняется payload `submit` и состав `candidate_users`. См. E17 §4.

---

## 0. TL;DR за 30 секунд

- Реализован модуль `rdmmesh-workflow` (SPEC §3.5, §2.2, §3.8):
  - 4-eyes state machine: `DRAFT → IN_REVIEW → STEWARD_APPROVED → OWNER_APPROVED` плюс reject-обратки в DRAFT с обязательным `comment`'ом. Публикация (`OWNER_APPROVED → PUBLISHED`) и `deprecate` намеренно откладываются на E6 — на E5 запрос с `to=PUBLISHED/DEPRECATED` валится `IllegalStateTransitionException` с указанием на E6, а не silent-noop.
  - Self-approval prevention: `actor ≠ created_by`, `actor ≠ previous_steward` — в pure-логике `StateMachine.validate()`.
  - Role gate: `OwnershipPort.rolesOf()` (asset-level) + base-role fallback (`RDM_STEWARD`/`RDM_OWNER`/`RDM_AUTHOR`/`RDM_ADMIN`). `RDM_ADMIN` — допустимый substitute для steward/owner, но self-approval-проверки на него тоже распространяются (SPEC §3.2 #7).
  - Append-only журнал `workflow.workflow_transition` + материализованная "My Tasks" (`workflow.approval_task` upsert/close).
  - Эмиссия `WorkflowTransitionEvent` через новый `SyncEventBus` — подписки на E10 (audit) и E6 (publishing).
- Новый порт `VersionLifecyclePort` в `rdmmesh-api`: write-side контракт `authoring.code_set_version`, чтобы workflow не лез в чужую schema (SPEC §3.3 — `authoring` пишет только модуль authoring). Реализация — `VersionLifecycleAdapter` в authoring.
- `AuthoringService.createDraft` теперь enforces **at-most-one open version** на CodeSet (закрывает follow-up из E4 §3 п.3): любая non-terminal версия (DRAFT/IN_REVIEW/STEWARD_APPROVED/OWNER_APPROVED) блокирует создание новой → 409.
- ArchUnit: правило `workflow_internal_only_used_by_workflow` переведено на strict (без `allowEmptyShould`).
- Smoke и `make verify` **не прогонялись** в этой сессии — см. §3.1.

---

## 1. Что сделано

### 1.1. Новые файлы

```
rdmmesh-api/src/main/java/bank/rdmmesh/api/port/
  └── VersionLifecyclePort.java          ← read+CAS-write контракт authoring'а

rdmmesh-authoring/src/main/java/bank/rdmmesh/authoring/internal/
  └── VersionLifecycleAdapter.java       ← реализация порта над CodeSetVersionDao

rdmmesh-app/src/main/java/bank/rdmmesh/app/eventbus/
  └── SyncEventBus.java                  ← простой in-process pub/sub (publish-time delivery)

rdmmesh-workflow/src/main/java/bank/rdmmesh/workflow/
  ├── WorkflowModule.java                ← composition factory (jdbi + 3 порта + EventBus)
  ├── internal/
  │   ├── StateMachine.java              ← pure-логика: матрица переходов, role gate, self-approval
  │   ├── PostgresWorkflowPort.java      ← thin адаптер WorkflowPort → WorkflowService
  │   ├── service/WorkflowService.java   ← оркестратор: ctx → SM → CAS → log → task → event
  │   └── dao/
  │       ├── WorkflowTransitionDao.java ← append-only INSERT + history()
  │       └── ApprovalTaskDao.java       ← upsertOpen / close / closeAll / findOpenByUser
  └── resource/
      ├── WorkflowTransitionResource.java  ← POST /versions/{id}/transitions, GET .../history
      └── MyTasksResource.java             ← GET /tasks/my (+ ApprovalTaskDto record)

rdmmesh-workflow/src/test/java/bank/rdmmesh/workflow/internal/
  └── StateMachineTest.java              ← 16 unit-тестов на pure-логику
```

### 1.2. Изменённые файлы

| Файл | Что изменилось |
|---|---|
| `rdmmesh-authoring/.../dao/CodeSetVersionDao.java` | Добавлены `findOpenVersions`, `casStatus`, `setApprover`, `recordReviewer`, `reviewersOf`. Существующие методы не тронуты. |
| `rdmmesh-authoring/.../service/AuthoringService.java` | `createDraft` теперь сначала проверяет `findOpenVersions(codesetId)` — at-most-one. Сообщение об ошибке указывает блокирующую версию. |
| `rdmmesh-authoring/.../AuthoringModule.java` | Добавлен `buildLifecyclePort(jdbi)`. |
| `rdmmesh-workflow/pom.xml` | Добавлены `jdbi3-sqlobject`, `dropwizard-auth` (для `@Auth`/`@RolesAllowed`). |
| `rdmmesh-app/pom.xml` | Добавлена зависимость `jdbi3-postgres` (для `PostgresPlugin`). |
| `rdmmesh-app/.../RdmmeshApplication.java` | `jdbi.installPlugin(new PostgresPlugin())` (нужен для `uuid[]` в `approval_task.candidate_users`). Wiring `WorkflowModule.build(jdbi, lifecycle, ownership, catalogReadPort, eventBus)` + регистрация двух resource'ов. |
| `rdmmesh-app/.../arch/ModuleIsolationTest.java` | `workflow_internal_only_used_by_workflow` переведено на strict-вариант (`internalSliceUsedOnlyByStrict`). |

**Миграций V031+ не требуется** — `workflow.workflow_transition` и `workflow.approval_task` уже созданы V030 в E1, и `authoring.code_set_version_reviewer` — V020. CHECK на published-версию (`content_hash IS NOT NULL`) не мешает E5, потому что мы в PUBLISHED не переходим.

### 1.3. State machine (E5 scope)

Полная матрица из `StateMachine.java` (значения, что генерируется в `Action`/`Status`):

| from | action | to | role gate | self-approval check | side-effect |
|---|---|---|---|---|---|
| DRAFT | submit | IN_REVIEW | actor=created_by ИЛИ base RDM_AUTHOR/ADMIN | — | — |
| IN_REVIEW | steward_approve | STEWARD_APPROVED | asset STEWARD ИЛИ base RDM_STEWARD/ADMIN | actor ≠ created_by | INSERT в `code_set_version_reviewer` |
| IN_REVIEW | steward_reject | DRAFT | то же | то же; comment обязателен | — |
| STEWARD_APPROVED | owner_approve | OWNER_APPROVED | asset OWNER ИЛИ base RDM_OWNER/ADMIN | actor ≠ created_by И actor ∉ reviewers | UPDATE `approved_by` |
| STEWARD_APPROVED | owner_reject | DRAFT | то же | то же; comment обязателен | — |
| OWNER_APPROVED | publish | PUBLISHED | **E6** | — | snapshot+content_hash+HMAC (E6) |
| PUBLISHED | deprecate | DEPRECATED | **E6** | — | — |

REJECTED — статус остаётся в enum для будущей семантики (например, hotfix decline) и просто не используется в основном flow MVP. В `code_set_version.status` CHECK его пропускает.

### 1.4. Изоляция транзакций (важно для будущих эпиков)

CAS статуса — в схеме `authoring` через `VersionLifecyclePort.transition`. Журнал и approval-task — в схеме `workflow` через отдельную `jdbi.useTransaction`. Это **две разные транзакции**, не одна. Если первая прошла, а вторая упала — статус ушёл, журнал не записался. На пилоте это допустимо: статус — single source of truth, журнал восстанавливается на следующих переходах. Но если потребуется криптографическая audit-цепочка (planned для V14), нужен будет либо Postgres `BEGIN;` поверх обеих схем (один Jdbi.handle), либо outbox-паттерн с idempotent processor'ом. Зафиксировать решение к **E10** (Audit).

### 1.5. EventBus

`SyncEventBus` лежит в `bank.rdmmesh.app.eventbus`, потому что его реализация — деталь composition root'а; модули видят только интерфейс из `rdmmesh-api`. Sync-доставка в потоке publisher'а; broken subscribers логируются и не обрушают transition (workflow рассматривает publish как "best effort" — статус и журнал уже зафиксированы).

Сейчас никто не подписан. На E6 publishing-модуль подпишется на `WorkflowTransitionEvent` с фильтром `to == PUBLISHED` (и сам же выполнит трансишн через `WorkflowPort` после создания snapshot'а — в обратном порядке). На E10 audit подпишется на `DomainEvent.class` в общем виде.

### 1.6. Authorization gate в REST

`POST /versions/{id}/transitions` защищён `@RolesAllowed({"RDM_AUTHOR","RDM_STEWARD","RDM_OWNER","RDM_ADMIN"})` — это грубый периметр (любой внутренний пользователь, не Consumer). Точная семантика — в `StateMachine` (asset+base role). Поэтому 403 от `@RolesAllowed` означает "ты вообще не имеешь доступа к workflow", а 403 от `InsufficientRoleException` — "конкретно этот transition тебе не разрешён".

`GET /tasks/my` — только `@Auth`, без `@RolesAllowed`: каждый видит только свои task'и (фильтр в SQL по `:user = ANY(candidate_users)`).

### 1.7. Что мы намеренно НЕ делали

- **publish и deprecate** — E6.
- **Notifications** (e-mail/Slack approver'ам, что у них task) — V1+, требует SMTP-конфига.
- **Approval tasks claim** (assign-to-self) — пилот их не требует, candidate_users достаточно.
- **Hotfix workflow** (BR-17, owner может публиковать без steward) — это V1+ через отдельный шаблон. На E5 не реализовано.
- **Custom BPMN per Domain** (BR-18) — V2+; решение остаётся за `WorkflowPort`.
- **Реальная интеграционная проверка через testcontainers** — отложена; см. §3 follow-up.

---

## 2. Контракт

### 2.1. REST

```
POST /api/v1/versions/{id}/transitions
  body: { "to": "IN_REVIEW", "comment": "...", "expected_status": "DRAFT" (optional) }
  auth: Bearer JWT, base role one of: RDM_AUTHOR | RDM_STEWARD | RDM_OWNER | RDM_ADMIN
  200 → WorkflowTransitionEvent (JSON: event_id, version_id, codeset_id, domain_id, from, to, action, actor, occurred_at, comment)
  400 → bad request (missing 'to')
  403 → role insufficient (asset+base)
  404 → version unknown
  409 → illegal transition / self-approval / publish-not-implemented / concurrent transition

GET /api/v1/versions/{id}/history
  auth: Bearer JWT
  200 → List<WorkflowTransitionEvent> в порядке occurred_at ASC
  404 → version unknown

GET /api/v1/tasks/my
  auth: Bearer JWT
  200 → List<ApprovalTaskDto> (id, version_id, codeset_id, domain_id, required_role, candidate_users, created_at)
```

### 2.2. Observed errors

| Условие | HTTP | Exception |
|---|---|---|
| `to` field missing | 400 | (raw) |
| `from` → `to` не из таблицы | 409 | `IllegalStateTransitionException` |
| target=PUBLISHED/DEPRECATED | 409 | `IllegalStateTransitionException` (с пометкой "E6") |
| reject без comment | 409 | `IllegalStateTransitionException` |
| actor=created_by на approve | 409 | `SelfApprovalException` |
| actor ∈ reviewers на owner_approve | 409 | `SelfApprovalException` |
| нет нужной роли | 403 | `InsufficientRoleException` |
| версия в другом статусе (concurrent) | 409 | `IllegalStateTransitionException` |
| Unknown version | 404 | `NotFoundException` |

---

## 3. Что осталось доделать в E5

### 3.1. ~~CRITICAL — прогнать build~~ ✅ DONE 2026-05-05

После включения Docker Desktop WSL Integration `make verify` показал три проблемы, **все исправлены в этой же сессии перед стартом E6**:

1. **Trailing whitespace в text-block** `ApprovalTaskDao.findOpenByUser`. Конструкция `"""\n SELECT """ + COLUMNS + """\n…"""` оставляла пробел после `SELECT` внутри text-блока — javac под `-Werror` бросал warning `trailing white space will be removed`. Переписан на тот же паттерн строковой конкатенации, что у `findOne` ниже в этом же файле.
2. **`WorkflowTransitionEvent` (POJO из rdmmesh-spec) не имплементит `DomainEvent`**. `EventBus.publish(<E extends DomainEvent>)` падал с inference error. rdmmesh-spec лежит ниже rdmmesh-api в графе зависимостей, поэтому `javaInterfaces: [DomainEvent]` в JSON Schema создаёт цикл. Решение — record-обёртка `bank.rdmmesh.api.eventbus.WorkflowTransitionDomainEvent(eventId, occurredAt, payload)`. REST-контракт нетронут (payload-POJO остаётся как был); audit (E10) подписывается на `DomainEvent.class` глобально, publishing (E6) — точечно на `WorkflowTransitionDomainEvent.class`.
3. **`ImportOption.DoNotIncludeJars` ломает strict-вариант ArchUnit-правил**. ArchUnit не сканировал классы из JAR'ов сестринских модулей (rdmmesh-workflow и т.д.) и `workflow_internal_only_used_by_workflow` падал с «failed to check any classes». Этот option выпилен из `@AnalyzeClasses` (фильтр `packages = "bank.rdmmesh"` сам отсекает сторонние библиотеки). Strict-варианты теперь работают корректно для всех модулей.

После этих правок:
- `make verify` зелёный, 69 тестов (19 StateMachineTest + 31 authoring + 8 JwtValidator + 11 ArchUnit).
- `make up` поднял весь стек (postgres+keycloak+rdmmesh-service healthy).
- Smoke 4-eyes flow по §3.2 прошёл полностью — actor=created_by 409, steward как reviewer 409, publish blocked 409, at-most-one-DRAFT 409, журнал + reviewer + approved_by в БД корректно (см. вывод ниже).

```
DRAFT → IN_REVIEW (submit) → STEWARD_APPROVED (steward_approve) → OWNER_APPROVED (owner_approve)
authoring.code_set_version_reviewer: 1 row (dev-steward om_user_id)
authoring.code_set_version.approved_by: dev-owner om_user_id
workflow.workflow_transition: 3 строки в строгой хронологии occurred_at
```

UUID[]-mapping в `ApprovalTaskDao` (см. §3.6 ниже) работает прямо из коробки с `PostgresPlugin` — fallback-план не понадобился.

### Замечание о `make kc-token` без `jq`

`Makefile` `kc-token` ожидает наличие `jq`; если его нет, target падает на `cat` и отдаёт сырой JSON Keycloak вместо access_token. На WSL без sudo установить jq нельзя — для smoke использовалось inline-парсинг через python3:

```bash
kc_token() { curl -s -X POST "http://localhost:8090/realms/bank/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=rdmmesh-ui -d username="$1" -d password=dev -d scope=openid \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])'; }
```

Стоит добавить python-fallback в Makefile (мягкий debt — не блокирует).

### 3.2. Smoke (после первого build)

```bash
make up
TOKEN_ADMIN=$(KC_USER=dev-admin make kc-token)
TOKEN_AUTHOR=$(KC_USER=dev-author make kc-token)
TOKEN_STEWARD=$(KC_USER=dev-steward make kc-token)
TOKEN_OWNER=$(KC_USER=dev-owner make kc-token)
HDR_AUTHOR="Authorization: Bearer $TOKEN_AUTHOR"

# 1. domain + CodeSet (через E3) — стандартное окружение
DOM_ID=$(curl -s -X POST -H "Authorization: Bearer $TOKEN_ADMIN" -H 'Content-Type: application/json' \
  -d '{"om_domain_id":"22222222-2222-2222-2222-222222222222","name":"risk_e5","display_name":"Risk E5"}' \
  http://localhost:8080/api/v1/domains | jq -r .id)

CS_ID=$(curl -s -X POST -H "$HDR_AUTHOR" -H 'Content-Type: application/json' \
  -d '{"name":"ifrs9_stages_e5","display_name":"IFRS9 stages E5","hierarchy_mode":"NONE",
       "initial_schema":{"type":"object","required":["stage"],
          "properties":{"stage":{"type":"string","enum":["1","2","3"]}}}}' \
  http://localhost:8080/api/v1/codesets/by-domain/$DOM_ID | jq -r .id)

# 2. DRAFT + items
V1=$(curl -s -X POST -H "$HDR_AUTHOR" -H 'Content-Type: application/json' -d '{}' \
  http://localhost:8080/api/v1/versions/by-codeset/$CS_ID | jq -r .id)
curl -X POST -H "$HDR_AUTHOR" -H 'Content-Type: application/json' \
  -d '{"key_parts":["S1"],"label_ru":"Stage 1","label_en":"Stage 1","attributes":{"stage":"1"}}' \
  http://localhost:8080/api/v1/versions/$V1/items
# (повторить для S2, S3)

# 3. submit DRAFT → IN_REVIEW
curl -X POST -H "$HDR_AUTHOR" -H 'Content-Type: application/json' \
  -d '{"to":"IN_REVIEW"}' \
  http://localhost:8080/api/v1/versions/$V1/transitions
#   → 200 + WorkflowTransitionEvent { from:"DRAFT", to:"IN_REVIEW", action:"submit" }

# 4. self-approval prevention: тот же author пробует steward_approve → 409
curl -X POST -H "$HDR_AUTHOR" -H 'Content-Type: application/json' \
  -d '{"to":"STEWARD_APPROVED"}' \
  http://localhost:8080/api/v1/versions/$V1/transitions
#   → 409 SelfApprovalException

# 5. dev-steward approves → STEWARD_APPROVED
curl -X POST -H "Authorization: Bearer $TOKEN_STEWARD" -H 'Content-Type: application/json' \
  -d '{"to":"STEWARD_APPROVED"}' \
  http://localhost:8080/api/v1/versions/$V1/transitions

# 6. dev-steward пробует owner_approve → 409 (он уже выступал steward'ом)
curl -X POST -H "Authorization: Bearer $TOKEN_STEWARD" -H 'Content-Type: application/json' \
  -d '{"to":"OWNER_APPROVED"}' \
  http://localhost:8080/api/v1/versions/$V1/transitions
#   → 409 SelfApprovalException ("уже выступал steward'ом")

# 7. dev-owner approves → OWNER_APPROVED
curl -X POST -H "Authorization: Bearer $TOKEN_OWNER" -H 'Content-Type: application/json' \
  -d '{"to":"OWNER_APPROVED"}' \
  http://localhost:8080/api/v1/versions/$V1/transitions

# 8. publish blocked (E6 ещё нет)
curl -X POST -H "Authorization: Bearer $TOKEN_OWNER" -H 'Content-Type: application/json' \
  -d '{"to":"PUBLISHED"}' \
  http://localhost:8080/api/v1/versions/$V1/transitions
#   → 409 IllegalStateTransitionException ("выполняется эпиком E6+")

# 9. history
curl -H "$HDR_AUTHOR" http://localhost:8080/api/v1/versions/$V1/history
#   → массив из 3 событий (submit, steward_approve, owner_approve)

# 10. my tasks для dev-owner — пусто (его последняя task закрыта при owner_approve, новой нет)
curl -H "Authorization: Bearer $TOKEN_OWNER" http://localhost:8080/api/v1/tasks/my
#   → []

# 11. at-most-one-DRAFT: попытка создать V2 при V1 в OWNER_APPROVED → 409
curl -X POST -H "$HDR_AUTHOR" -H 'Content-Type: application/json' -d '{}' \
  http://localhost:8080/api/v1/versions/by-codeset/$CS_ID
#   → 409 "CodeSet ... уже имеет открытую версию ... (OWNER_APPROVED)"

# 12. owner_reject обратно в DRAFT, потом V2 создаётся
curl -X POST -H "Authorization: Bearer $TOKEN_OWNER" -H 'Content-Type: application/json' \
  -d '{"to":"DRAFT","comment":"откат для теста"}' \
  http://localhost:8080/api/v1/versions/$V1/transitions
#   → 200, статус DRAFT
# Теперь V2 всё ещё блокируется (V1 в DRAFT). Сначала надо удалить V1:
curl -X DELETE -H "$HDR_AUTHOR" http://localhost:8080/api/v1/versions/$V1
curl -X POST -H "$HDR_AUTHOR" -H 'Content-Type: application/json' -d '{}' \
  http://localhost:8080/api/v1/versions/by-codeset/$CS_ID
#   → 201 V2

# 13. БД-проверка журнала
docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -c "
  SELECT version_id, from_status, to_status, action, actor, occurred_at
    FROM workflow.workflow_transition
   ORDER BY occurred_at;"
docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -c "
  SELECT version_id, om_user_id, reviewed_at FROM authoring.code_set_version_reviewer;"
docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -c "
  SELECT id, status, approved_by FROM authoring.code_set_version WHERE id = '$V1';"
```

### 3.3. Снять `allowEmptyShould(true)` с оставшихся правил

В `ModuleIsolationTest.java`:
- `audit_only_depends_on_api_or_spec` — снять при первом классе в `rdmmesh-audit` (E10).
- `publishing_internal_only_used_by_publishing` — E6.
- `distribution_internal_only_used_by_distribution` — E8.
- `audit_internal_only_used_by_audit` — E10.
- `distribution_does_no_db_writes` — E8.

E5 сделал только `workflow_internal_only_used_by_workflow` (строгое).

### 3.4. Эффект на старые E4 smoke-сценарии

Ранее (до E5) в smoke E4 §2 две версии создавались подряд: `V1` (DRAFT), затем `V2` (DRAFT) — для проверки diff. После E5 это даст 409 на втором POST, потому что `V1` ещё в DRAFT и блокирует создание V2.

Способ воспроизвести diff-сценарий после E5 — пройти full lifecycle для V1 (`submit → steward_approve → owner_approve → publish`); но `publish` доступен только с E6. До E6 единственный обходной путь для тестирования diff — удалить V1 (`DELETE /versions/{V1}` пока он DRAFT) и создать V2; либо ограничиться unit-тестом `DiffCalculatorTest` (он покрывает поведение полностью).

### 3.5. Atomicity transition log (см. §1.4)

Решить к E10 (Audit), нужен ли 2PC / outbox для согласованности `code_set_version.status` и `workflow.workflow_transition`. На пилоте — допустимо.

### 3.6. UUID[] mapping проверить под нагрузкой

`PostgresPlugin` должен покрыть `uuid[]` bind+read для `approval_task.candidate_users`. Если в smoke упадёт на чтении — fallback-план в §3.1. Я выбрал именно `uuid[]` (а не `text[]` или `jsonb`), чтобы держать схему типизированной и иметь GIN-индекс по UUID-массиву (V030 уже его создаёт).

---

## 4. Технический долг и решения, повлиявшие на следующие эпики

| Что | Где | Когда снять / следующий шаг |
|---|---|---|
| `WorkflowPort.openTaskFor` возвращает `Optional<AssetOwnership>` (зашит в API на E1) — текущая реализация всегда `Optional.empty()` | `PostgresWorkflowPort` | По-хорошему изменить контракт `WorkflowPort.openTaskFor` на список `ApprovalTask`. Сделать это **с E10**, когда подписчики аудита потребуют богатый event payload. |
| `EventBus` — sync, в-процессе. Подписчик может закрыть события `RuntimeException`'ом — ловится и логируется | `SyncEventBus` | Если на E10 потребуется реальная гарантия доставки в audit — outbox + worker. На пилоте sync достаточно. |
| Транзакционный split: status (authoring) + log (workflow) | `WorkflowService.transition` | E10 решит, нужен ли atomic |
| at-most-one **открытая** версия (а не "не больше одного DRAFT") | `AuthoringService.createDraft` | По SPEC §2.2 — корректно. Изменить только если бизнес попросит relaxation. |
| `Action.publish` / `Action.deprecate` — выбрасывают на E5 | `StateMachine.E5_IMPLEMENTED` | E6 включит их в `E5_IMPLEMENTED` (или, точнее, переименует флаг + расширит логику публикации) |
| `RDM_ADMIN` как substitute для steward/owner — рабочий, но self-approval всё равно блокируется | `StateMachine.requireStewardRole/requireOwnerRole` | Документация для пользователей: "RDM_ADMIN не может одобрить собственный draft" |
| `PostgresPlugin` теперь ставится глобально для Jdbi | `RdmmeshApplication.run` | Безопасно, но если другому модулю это поведение сломает — изолировать через `Jdbi.installPlugin` per-DAO (нет такого механизма; вариант — отдельный Jdbi instance). |

---

## 5. Указатели на следующие эпики

> Конкретное содержание — в SPEC §5.1.

### E6. Publishing (следующий)

- **Где:** `rdmmesh-publishing`. Сейчас модуль пуст (`pom.xml` + `src/main/java/.gitkeep`).
- **Что реализовать:**
  - Подписчик на `WorkflowTransitionEvent` с фильтром `to == OWNER_APPROVED`. После каждого OWNER_APPROVED — автоматический snapshot + publish.
  - Snapshot creation: SELECT-INTO в новой структуре (или просто freeze существующих `code_item` записей через `version_id` + UPDATE `system_to`). Пометить snapshot deterministic'ным content_hash'ом.
  - **content_hash:** SHA-256 от детерминированной (отсортированной по ключам) JSON-сериализации snapshot'а. Алгоритм нужно зафиксировать в коде и в `docs/`. Тест: hash должен быть стабильным относительно порядка items в БД.
  - **HMAC signature:** через новый `SecretsPort` или в `IdentityPort`-расширении. **В БД секрет НЕ хранить** — Vault/SOPS. CHECK constraint в V020 уже требует non-null `content_hash` + `approval_signature` для PUBLISHED.
  - После snapshot'а — позвать `WorkflowPort.transition(versionId, "PUBLISHED", system_user, "auto-publish after owner_approve")`. Чтобы это сработало, в state machine нужно "разрешить" переход OWNER_APPROVED→PUBLISHED — снять из `E5_IMPLEMENTED` блок-список. Я бы перевёл флаг на `E6_FROM_VERSION` или просто открыл список заново.
  - REST `GET /versions/{id}/verify` (SPEC §3.8) — пересчитать `content_hash` из текущих items версии и сверить с записанным. Возвращает `{verified: bool, computed_hash, stored_hash}`.
  - Иммутабельность: REVOKE UPDATE/DELETE на `authoring.code_item` для PUBLISHED-версий — либо через триггер, либо через текущий WHERE-clause `v.status='DRAFT'` в DAO (уже стоит).
- **Зависимости:** E5 ✓, E2 ✓.
- **Снять `allowEmptyShould` с** `publishing_internal_only_used_by_publishing` после первого класса в `internal..`.

### E7. Ownership webhook

Без изменений с E4 §5. После E7 в `rdm_asset_ownership` появятся реальные STEWARD'ы — `/tasks/my` начнёт показывать им задачи (сейчас, до E7, candidate_users для STEWARD пустой).

### E8 / E9 / E10 / E11 / E12 / E13 / E14 — см. SPEC §5.1.

---

## 6. Открытые вопросы (актуальны для команды банка)

Без изменений с E2/E3/E4:

1. Production-Strategy для Flyway — подтверждено: `autoMigrate=false` в prod, миграции отдельным шагом.
2. Реальные prod-параметры Keycloak (issuer/jwks/audience/client_secret).
3. OM API base URL и bot-токен.
4. **HMAC secret rotation policy** — теперь блокирует E6.
5. Нужны ли уведомления (e-mail/Slack) approver'ам по «My Tasks» — V1+ scope. Сейчас пользователь сам периодически опрашивает `/tasks/my`.
6. **Как должен вести себя `RDM_ADMIN` при substitution для steward/owner?** В E5 admin может выступать substitute'ом, но self-approval блокируется. Если бизнес хочет полностью запретить substitution (всегда требовать asset-level role) — изменить `StateMachine.requireStewardRole/requireOwnerRole`, убрав base-role fallback.

---

## 7. Версия документа

- **0.1** — 2026-05-05. Создан после реализации E5 (Workflow). Build/smoke не прогонялись из-за отсутствия Docker WSL Integration; пользователь должен прогнать `make verify` после включения Docker (см. §3.1). Автор: Claude Opus 4.7.
