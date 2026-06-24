# Handoff — Эпик E17 (Адресная маршрутизация согласования + справочник ролей домена)

> **Аудитория документа.** AI-агенты и инженеры, которые будут **реализовывать**
> E17. Документ самодостаточен — переписки и контекста сессии у вас нет, всё что
> нужно — здесь, в [`SPEC.md`](../../SPEC.md) (§2.1, §2.2 «Адресная маршрутизация
> согласования», §2.4 «Справочник ролей домена», §2.5 BR-21/BR-22, §3.4, §3.5,
> §4.3 ADR-009, §5.1), [`E5-workflow.md`](E5-workflow.md),
> [`E7-ownership.md`](E7-ownership.md), [`E11-ui.md`](E11-ui.md),
> [`E11.2-ui-editing.md`](E11.2-ui-editing.md) и [`E16-flowable.md`](E16-flowable.md).
>
> **Дата.** 2026-05-19.
> **Состояние.** **РЕАЛИЗОВАНО 2026-05-19** (локально; commit по запросу
> пользователя). `./bin/mvn -DskipITs verify` — BUILD SUCCESS, StateMachineTest
> 22/22, WorkflowGraphInvariantsTest 13/13, ArchUnit 11/11 (нулевая регрессия);
> UI `npm run typecheck`/`build` зелёные; e2e-smoke на чистом `make up` пройден
> (см. §9 + ниже). Реализация следует дизайну ниже со следующими
> уточнениями/деviation'ами: (a) `submit` несёт **обоих** согласующих
> (`assignee.steward_om_user_id` + `owner_om_user_id`) — owner-этап тоже
> адресный (закрывает open question §10 q2); (b) reload-эндпоинт —
> `POST /api/v1/admin/domain-role-directory/reload` (без `:reload`-двоеточия);
> (c) «не в справочнике / чужой домен» → **409** (§10 q1 решён в пользу 409),
> отсутствует assignee на submit → 400; (d) assignee переносится REST→service
> через ThreadLocal `SubmitAssigneeHolder` (сигнатура `WorkflowEngine.transition`
> не менялась — Flowable-путь не тронут); (e) `WorkflowService` опционально
> принимает `ApproverDirectoryPort` (null → legacy broadcast, обратная
> совместимость ITs); (f) approvers — отдельный root-resource
> `@Path("/domains/{domainId}/approvers")` (иначе JAX-RS 404 из-за catalog
> `@Path("/domains")`). Миграции: ownership **V062**, workflow **V034**.
> **Зависимости:** E5 (state machine, `approval_task`) ✓, E7 (`ownership`-модуль,
> `rdm_asset_ownership`) ✓, E11.2a (`WorkflowActions`, submit-кнопка, MyTasksPage) ✓.

---

## 0. TL;DR за 30 секунд

- Меняем модель маршрутизации `submit` (`DRAFT → IN_REVIEW`): вместо «вещания»
  задачи всем asset-стюардам — **Author адресно выбирает согласующего**: домен →
  роль (`Steward`/`Business Owner`) → конкретную учётную запись.
- Кандидаты приходят из **нового справочника** `ownership.domain_role_directory`
  (`домен → роль → учётка`). Мастер — OpenMetadata; **сейчас локальный сид**,
  позже — справочник, сгенерированный в OM. Обновление = **`TRUNCATE` + `INSERT`**
  одной транзакцией (полная замена, НЕ дельта как у webhook'а E7).
- `submit` принимает `assignee = {domain_id, role, om_user_id}`; state machine
  валидирует тройку по справочнику + сохраняет self-approval-проверку. Создаётся
  **адресная** `approval_task` (`candidate_users = [assignee]`, `assigned_role`).
- В «Мои задачи» выбранного пользователя появляется задача; по клику — переход
  на **уже реализованную** страницу draft-версии (`VersionPage`) с кнопками
  `Steward approve` / `Steward reject` (E11.2a `WorkflowActions`).
- `BUSINESS_OWNER` домена = владелец домена в терминах OM (тот же субъект, что
  приходит как `OWNER` для `entityType=domain`). Новой governance-роли не вводим.

Маппинг 7 требований → разделы: (1)→§6 UI; (2)→§6 UI + §3 approvers; (3)→§2/§7
справочник+мастер OM; (4)→§2 TRUNCATE+INSERT reload; (5)→§7 локальный сид; (6)→§4
адресная `approval_task` + §6 MyTasks; (7)→§6 (страница draft'а уже есть).

---

## 1. Постановка (из требований заказчика)

1. В UI у пользователя — выбор, **кому** он отправляет draft справочника на
   согласование.
2. Пользователь выбирает **домен** и **учётную запись** другого пользователя —
   стюарда **или** бизнес-владельца этого домена.
3. Справочник `домен → роль(стюард, бизнес-владелец) → учётная запись` —
   мастер-система **OpenMetadata** (источник истины по DG-ролям).
4. Обновление справочника на стороне RDM — **очистка (`TRUNCATE`)** старых
   записей и **запись (`INSERT`)** данных из нового справочника.
5. Пока — **свой (локальный) справочник**; позже заменяем на сгенерированный
   в OpenMetadata.
6. После выбора согласующего у него в разделе **«Мои задачи»** появляется
   задача на согласование.
7. По клику на задачу система открывает **страницу draft справочника**, где он
   делает `Steward approve` / `Steward reject` — **страница уже реализована**
   (E11.2a `WorkflowActions` на `VersionPage`).

---

## 2. Доменная модель и миграции

### 2.1. Новая таблица `ownership.domain_role_directory`

Владелец данных — модуль `ownership` (он уже владеет schema `ownership` и
семантикой синхронизации ролей из OM, см. [`E7-ownership.md`](E7-ownership.md)
§1.2). **Не** трогаем `rdm_asset_ownership` (per-CodeSet, дельта-UPSERT) и
**не** трогаем `catalog.domain` — это отдельный домен-скоупный справочник.

Миграция `bootstrap/sql/migrations/ownership/V0xx__domain_role_directory.sql`
(номер — следующий свободный в `ownership/`; на момент E7 был V061, проверьте
актуальный максимум перед написанием):

```sql
CREATE TYPE ownership.domain_directory_role AS ENUM ('STEWARD', 'BUSINESS_OWNER');

CREATE TABLE ownership.domain_role_directory (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    domain_id     uuid NOT NULL REFERENCES catalog.domain(id),
    role          ownership.domain_directory_role NOT NULL,
    om_user_id    uuid NOT NULL,
    username      text NOT NULL,
    display_name  text,
    source        text NOT NULL DEFAULT 'LOCAL_SEED',  -- LOCAL_SEED | OM_GENERATED
    loaded_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (domain_id, role, om_user_id)
);

CREATE INDEX idx_drd_domain_role ON ownership.domain_role_directory (domain_id, role);
```

Замечания:
- FK на `catalog.domain(id)` (а не `om_domain_id`) — согласовано с тем, что
  reload получает доменный идентификатор и резолвит его в локальный mirror.
  Если в снапшоте приходит `om_domain_id` — резолвить через `catalog.domain`
  (тот же приём, что webhook E7).
- `username/display_name` денормализованы намеренно (SPEC §2.4): UI строит
  список согласующих без синхронного похода в OM (слабая связанность, §3.2 п.4).
- `source` — провенанс строки (`LOCAL_SEED` сейчас, `OM_GENERATED` потом);
  диагностический, downstream его не консультирует.

### 2.2. Изменение `workflow.approval_task`

E5 уже создал `approval_task` с `candidate_users uuid[]` (V030, см.
[`E5-workflow.md`](E5-workflow.md) §1.2/§3.6). Для адресности достаточно
писать массив из **одного** элемента — схему ломать не нужно. Добавляем
только колонку роли для отображения/аудита:

`bootstrap/sql/migrations/workflow/V0xx__approval_task_assigned_role.sql`:

```sql
ALTER TABLE workflow.approval_task
    ADD COLUMN assigned_role text;   -- 'STEWARD' | 'BUSINESS_OWNER' | NULL (legacy/broadcast)
```

`NULL` = старое broadcast-поведение (обратная совместимость; на пилоте новых
broadcast-submit'ов не будет, но миграция не должна ломать существующие строки).

---

## 3. Контракт REST

### 3.1. `POST /api/v1/versions/{id}/transitions` — расширение тела

При `to=IN_REVIEW` тело обязано содержать `assignee`:

```jsonc
{
  "to": "IN_REVIEW",
  "comment": "...",                 // опционально для submit
  "expected_status": "DRAFT",       // как и раньше (E11.2a §1.4)
  "assignee": {
    "domain_id":  "<uuid>",         // = домен CodeSet'а; UI подставляет, backend сверяет
    "role":       "STEWARD",        // STEWARD | BUSINESS_OWNER
    "om_user_id": "<uuid>"          // выбранная учётная запись
  }
}
```

Валидация (в `StateMachine`/`WorkflowService`, см. §4):
- `assignee` отсутствует при `to=IN_REVIEW` → **400**.
- тройка `(domain_id, role, om_user_id)` не найдена в `domain_role_directory`
  → **422** (или 409 — единообразно с прочими workflow-ошибками; решить, см. §10).
- `assignee.domain_id` ≠ домен CodeSet'а версии → **422**.
- `assignee.om_user_id == created_by` → **409** `SelfApprovalException`
  (правило self-approval сохраняется, SPEC §3.8).

Прочие `to` (steward/owner approve/reject) — без изменений; `assignee`
игнорируется/не требуется.

### 3.2. `GET /api/v1/domains/{domain}/approvers?role=STEWARD|BUSINESS_OWNER`

Список кандидатов-согласующих для UI submit-диалога. Источник —
`domain_role_directory`. Любой authenticated (Author должен видеть, кому
отправлять). Ответ:

```json
[ { "om_user_id": "...", "username": "...", "display_name": "...", "role": "STEWARD" } ]
```

`role` опционален: без него — все роли домена; с ним — фильтр.

### 3.3. `POST /api/v1/admin/domain-role-directory:reload`

`@RolesAllowed("RDM_ADMIN")`. Тело — **полный снапшот** справочника:

```jsonc
{ "entries": [ { "om_domain_id": "...", "role": "STEWARD",
                 "om_user_id": "...", "username": "...", "display_name": "..." } ] }
```

Семантика — **полная замена одной транзакцией**:

```
BEGIN;
  TRUNCATE ownership.domain_role_directory;
  INSERT ... SELECT (резолв om_domain_id → catalog.domain.id);
COMMIT;
```

Идемпотентно (повторный одинаковый снапшот → тот же результат). Источник тела
сейчас — локальный сид (§7), позже — экспорт справочника, сгенерированного в OM
(тело то же, меняется только кто его готовит → `source` ставится вызывающим
или по флагу).

---

## 4. Изменения в `workflow` (E5) и новый порт

- Новый порт в `rdmmesh-api`: **`ApproverDirectoryPort`**
  - `boolean isAssignableApprover(UUID domainId, DirectoryRole role, UUID omUserId)`
  - `List<Approver> approversOf(UUID domainId, Optional<DirectoryRole> role)`
  - Реализация — `PostgresApproverDirectoryAdapter` в модуле `ownership`
    (он владеет таблицей). Регистрируется в `OwnershipModule`, прокидывается
    в `WorkflowModule.build(...)` так же, как сейчас `OwnershipPort` (см.
    [`E5-workflow.md`](E5-workflow.md) §1.2 wiring).
- `StateMachine` для `submit`: после существующих проверок добавить
  `ApproverDirectoryPort.isAssignableApporver(...)` + domain-match + self-approval
  на `assignee.om_user_id`. Логика остаётся в pure-`StateMachine.validate()`
  там, где это возможно (тестируемость — `StateMachineTest`).
- `WorkflowService` при успешном `submit`: создаёт `approval_task` с
  `candidate_users = [assignee.om_user_id]`, `assigned_role = role` (вместо
  текущего `ApprovalTaskDao.upsertOpen` с broadcast-кандидатами).
  `GET /tasks/my` уже фильтрует `:user = ANY(candidate_users)` (E5 §1.6) —
  адресная задача автоматически видна **только** выбранному пользователю.
- **Flowable (E16).** Делегат `WorkflowTransitionDelegate` зовёт тот же
  `WorkflowService.transition` ([`E16-flowable.md`](E16-flowable.md) §0), т.е.
  изменения в `WorkflowService`/`StateMachine` автоматически работают и для
  `RDM_WORKFLOW_ENGINE=flowable` — отдельной правки BPMN не требуется (топология
  `rdm4eyes` зеркалит enum-матрицу; набор переходов не меняется, меняется только
  payload `submit` и side-effect создания task'и).

Коды ошибок REST — без новых HTTP-семантик, кроме добавленного 400/422 для
`assignee` (см. §3.1). Таблица «Observed errors» из E5 §2.2 расширяется
соответствующими строками — отразить при реализации.

---

## 5. Размещение по модулям (ArchUnit)

| Что | Модуль | Почему |
|---|---|---|
| `domain_role_directory` таблица, DAO, reload, approvers-resource | `ownership` | владеет schema `ownership` и OM-sync-семантикой ролей (E7) |
| `ApproverDirectoryPort` (интерфейс) | `rdmmesh-api` | межмодульный контракт (как `OwnershipPort`) |
| Консультация порта в `submit` | `workflow` | state machine — единственный gate переходов |
| submit-диалог, approvers-fetch, MyTasks→Version навигация | `rdmmesh-ui` | E11 |

ArchUnit: `workflow` обращается к справочнику **только** через
`ApproverDirectoryPort` из `rdmmesh-api` (никаких прямых импортов `ownership..`,
SPEC §3.3). Reload/approvers-resource — внутри `ownership`, как
`OwnershipWebhookResource` (E7 §1.3).

---

## 6. Изменения UI (E11)

Страница draft-версии с `Steward approve/reject` **уже есть** — E11.2a
`WorkflowActions` на `VersionPage` (см. [`E11.2-ui-editing.md`](E11.2-ui-editing.md)
§1.6). Новое:

1. **Submit-диалог.** Сейчас `WorkflowActions` для `DRAFT` показывает кнопку
   `submit` без параметров. Заменить на кнопку, открывающую AntD `Modal`/`Form`:
   - **Домен** — `Select`, предзаполнен доменом CodeSet'а (берётся из уже
     загруженного `getCodeSet`). Можно оставить заблокированным/readonly (один
     домен у CodeSet'а), но показать явно — требование (2).
   - **Роль** — `Select`: `Steward` / `Business Owner`.
   - **Учётная запись** — `Select`, опции из
     `GET /domains/{domainId}/approvers?role=...` (lazy, по выбору роли;
     `@tanstack/react-query`, ключ `qk.domains.approvers(domainId, role)` —
     добавить в `queryClient.ts`).
   - Комментарий — опционально.
   - Submit → `apiMutations.transition(versionId, {to:"IN_REVIEW",
     expected_status, assignee:{domain_id,role,om_user_id}, comment})`.
     Инвалидация — как в E11.2a §1.6 (`getVersion`, `transitionHistory`,
     `byCodeset`, `codesets.one`, `tasks.my`).
   - Ошибки: 422 «согласующий не найден в справочнике / чужой домен» и 409
     self-approval → `message.error` с текстом backend'а (frontend роли не
     валидирует — принцип E11.2a §1.6).
2. **MyTasksPage → переход на draft.** Задача уже содержит `versionId`
   (E5 `ApprovalTaskDto`). Сделать строку/кнопку кликабельной →
   `navigate('/versions/{versionId}')`. Там пользователь видит `WorkflowActions`
   с `Steward approve/reject` (IN_REVIEW). Опционально показать `assigned_role`
   в задаче. (MyTasksPage существует с E11 round 1 §1.2.)
3. i18n: добавить ключи `submit.dialog.*`, `submit.role.steward/businessOwner`,
   `tasks.openDraft` в `ru.json`/`en.json`.

Frontend-codegen: при необходимости расширить `src/api/types.ts` (`Approver`,
`AssigneeBody`) — wire-типы хендкрафтятся (E11 §1.4).

---

## 7. Локальный сид сейчас, OM-генерация позже

- **Сейчас (требование 5).** Завести сид-источник для `domain_role_directory`,
  чтобы submit-flow работал на пилоте/в smoke без OM. Варианты (выбрать при
  реализации, см. §10): (a) seed-миграция Flyway
  `ownership/V0xx__domain_role_directory_seed.sql` с dev-доменами и
  `dev-steward`/`dev-owner`; (b) seed-файл (JSON в `bootstrap/seed/`),
  заливаемый через reload-эндпоинт в `seed-demo.sh`. Рекомендация — (b):
  тот же путь, что прод-reload, и не «зашивает» данные в миграции.
  В обоих случаях `source='LOCAL_SEED'`.
- **Позже.** OM генерирует справочник DG-ролей (data product/отчёт). Его
  экспорт подаётся в тот же `:reload` (`source='OM_GENERATED'`). Downstream
  (UI, state machine) не меняется — это и есть смысл ADR-009 (источник
  абстрагирован за единой точкой полной замены).
- **Не делаем в E17:** автоматический pull/синк из OM (cron, webhook,
  ingestion). Только ручной/скриптовый `:reload`. Автосинхронизация — отдельный
  эпик после того, как в OM появится генерируемый справочник (open question).

---

## 8. Что НЕ входит в E17 (намеренная граница)

- Не меняем owner-этап 4-eyes (`STEWARD_APPROVED → OWNER_APPROVED`) — адресуется
  только steward-этап (требования 6–7 про задачу согласования и страницу
  approve/reject). Нужно ли адресовать и owner-этап — open question §10.
- Не меняем `rdm_asset_ownership`/webhook E7 — это другой канал с другой
  семантикой консистентности (ADR-009).
- Не делаем уведомления (e-mail/Slack) выбранному согласующему — это
  by-design V1+ (E5 §1.7, открытый вопрос команды банка).
- Не делаем автоматический синк справочника из OM (см. §7).
- Не вводим per-domain BPMN-специфику (это E16.2+, ортогонально).

---

## 9. План smoke (для агента-реализатора)

```bash
make up
TADM=$(KC_USER=dev-admin make kc-token)
TAUT=$(KC_USER=dev-author make kc-token)
TST=$(KC_USER=dev-steward make kc-token)

# 0. reload справочника (локальный сид) — TRUNCATE+INSERT
curl -sS -X POST -H "Authorization: Bearer $TADM" -H 'Content-Type: application/json' \
  -d @bootstrap/seed/domain-role-directory.json \
  http://localhost:8080/api/v1/admin/domain-role-directory:reload
# повторный тот же reload → идемпотентно (та же выборка)

# 1. approvers домена
curl -sS -H "Authorization: Bearer $TAUT" \
  "http://localhost:8080/api/v1/domains/$DOM_ID/approvers?role=STEWARD"
#   → [{om_user_id: <dev-steward>, role:"STEWARD", ...}]

# 2. submit с assignee=dev-steward
curl -sS -X POST -H "Authorization: Bearer $TAUT" -H 'Content-Type: application/json' \
  -d '{"to":"IN_REVIEW","expected_status":"DRAFT",
       "assignee":{"domain_id":"'$DOM_ID'","role":"STEWARD","om_user_id":"<dev-steward>"}}' \
  http://localhost:8080/api/v1/versions/$V1/transitions
#   → 200, status IN_REVIEW

# 3. /tasks/my у dev-steward → ровно одна адресная задача (assigned_role=STEWARD)
curl -sS -H "Authorization: Bearer $TST" http://localhost:8080/api/v1/tasks/my
# 3b. /tasks/my у произвольного другого стюарда → пусто (адресность)

# 4. submit с assignee==created_by (dev-author) → 409 SelfApproval
# 5. submit с тройкой не из справочника → 422
# 6. submit без assignee при to=IN_REVIEW → 400
# 7. dev-steward открывает /versions/$V1 в UI → Steward approve → STEWARD_APPROVED
#    (далее owner-этап — без изменений, как E5 §3.2)

# БД-проверки
docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -c \
 "SELECT domain_id, role, username, source FROM ownership.domain_role_directory ORDER BY role;"
docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -c \
 "SELECT version_id, candidate_users, assigned_role FROM workflow.approval_task WHERE version_id='$V1';"
```

> Памятка из памяти проекта: `jq` в окружении нет — используйте python3-инлайн
> для парсинга токена (см. [`E5-workflow.md`](E5-workflow.md) §3.1). Docker
> доступен — **реально прогнать `make up` и smoke**, не ограничиваться сборкой.

---

## 10. Открытые вопросы (для команды банка)

Без изменений с предыдущих эпиков, плюс:

1. **HTTP-код для «assignee не в справочнике / чужой домен»** — 422 или 409?
   (Единообразие с workflow-ошибками E5 §2.2 vs. семантика «невалидный ввод».)
2. **Адресовать ли owner-этап** (`STEWARD_APPROVED → OWNER_APPROVED`) так же,
   как steward-этап, или owner остаётся из `rdm_asset_ownership` (как сейчас)?
   Требования 6–7 явно говорят только про steward approve/reject.
3. **Источник локального сида** — seed-миграция Flyway vs. seed-файл через
   `:reload` в `seed-demo.sh` (рекомендация §7 — файл).
4. **Когда и как** OM начнёт генерировать справочник DG-ролей и в каком формате
   экспорт (для перехода `LOCAL_SEED → OM_GENERATED` без изменения контракта
   `:reload`).
5. **Автосинк** справочника из OM (cron/ingestion) — отдельный эпик после
   появления OM-генерации; нужен ли вообще или достаточно ручного `:reload`.
6. **Уведомления** выбранному согласующему (e-mail/Slack) — V1+ (как E5 §6 п.5).
7. **`assignee.domain_id` vs домен CodeSet'а.** Сейчас они обязаны совпадать
   (CodeSet принадлежит одному домену). Подтвердить, что cross-domain
   маршрутизация не требуется.

---

## 11. Версия документа

- **0.1** — 2026-05-19. Спецификация эпика E17 (адресная маршрутизация
  согласования + `domain_role_directory`). Реализация не начата. Создан после
  обновления `SPEC.md` (§2.1/2.2/2.4/2.5/3.4/3.5/4.3 ADR-009/5.1) под
  требования BR-21/BR-22. Автор: Claude Opus 4.7.
