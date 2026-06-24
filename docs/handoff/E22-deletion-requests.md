# E22 — Author→Admin запрос на удаление справочника (Slice A)

## TL;DR

Author не может удалить CodeSet самостоятельно (это `@RolesAllowed("RDM_ADMIN")`).
Чтобы убрать ошибочно созданный справочник, Author **создаёт заявку** на
удаление с обязательным объяснением. Заявка попадает в очередь к Admin'у,
тот approve/reject. Approve = soft-delete CodeSet'а (`deleted_at`),
PUBLISHED-версии физически не удаляются (SPEC §3.7, IFRS9 retention).

Workflow: `PENDING → APPROVED | REJECTED | CANCELLED`.

**Status:** Slice A — author submit + admin decide + UI на обеих сторонах.
OM-уведомление owner'а — out of scope, см. §«Дальше».

## Workflow

```
                  ┌──────────────┐
   Author submit  │   PENDING    │  Admin approve → soft-delete CodeSet
   ──────────────▶│              │ ───────────────────▶ [APPROVED]
                  │              │
                  │              │  Admin reject (с комментом)
                  │              │ ───────────────────▶ [REJECTED]
                  │              │
                  │              │  Author cancel
                  │              │ ───────────────────▶ [CANCELLED]
                  └──────────────┘
```

- **APPROVED / REJECTED / CANCELLED — terminal.** Из них нет возврата;
  Author создаёт новую заявку, если решение нужно пересмотреть.
- **Только один PENDING на CodeSet** одновременно (partial unique index).
  Попытка повторного submit пока есть PENDING → 409.
- **`requested_by ≠ decided_by`** — admin не может «сам себе» подтвердить
  свою же заявку (если admin случайно ещё и Author).

## Что в этом слайсе

| Часть | Где | Что делает |
|------|-----|-----------|
| Migration | `bootstrap/sql/migrations/catalog/V015__codeset_deletion_request.sql` | Таблица + индексы + partial unique |
| DAO | `rdmmesh-admin/.../dao/AdminDeletionRequestDao.java` | CRUD + state transitions |
| DTO | `rdmmesh-admin/.../dto/AdminDeletionRequestView.java` | REST view с join'ом codeset.name |
| Service | `rdmmesh-admin/.../AdminDeletionRequestService.java` | submit/cancel/approve/reject + soft-delete на approve |
| REST (author) | `rdmmesh-admin/.../resource/DeletionRequestResource.java` | `/codesets/{id}/deletion-requests`, `/deletion-requests/my`, `:cancel` |
| REST (admin) | `rdmmesh-admin/.../resource/AdminDeletionRequestResource.java` | `/admin/deletion-requests`, `:approve`, `:reject` |
| Wiring | `AdminModule.java`, `RdmmeshApplication.java` | Регистрация в jersey |
| UI request | `rdmmesh-ui/src/components/RequestDeletionButton.tsx` | Кнопка + Modal с textarea reason |
| UI queue | `rdmmesh-ui/src/pages/AdminDeletionRequestsPage.tsx` | Очередь admin'а: list+approve+reject |
| Wiring UI | `pages/CodeSetPage.tsx`, `App.tsx` (route), `Layout.tsx` (menu) | Кнопка на CodeSetPage + admin-menu |
| i18n | `i18n/{ru,en}.json` | Блок `deletionRequest.*` |

## Что НЕ в этом слайсе

- **Уведомление OM-Owner'а** через webhook или email. Заявка идёт сразу
  Admin'у. 4-eyes Owner + Admin — Slice B.
- **Email/Slack/OM-task** уведомление admin'у. Admin сам открывает
  queue-страницу. Push-нотификации — Slice C.
- **Hard-delete** (DELETE FROM code_set). Никогда не делаем по заявке —
  PUBLISHED-версии под IFRS9 retention. Если действительно нужен hard
  (ошибочно созданный CodeSet без PUBLISHED) — Admin делает это руками
  через existing `DELETE /admin/codesets/{id}?force_archive=true`
  (E18 — это про soft, но с обходом проверки).
- **Bulk-approve/bulk-reject**. Только по одной за раз.
- **Undelete** (восстановление soft-deleted CodeSet). Это отдельная
  admin-операция, не часть workflow заявок.

## Контракт БД

```sql
CREATE TABLE catalog.code_set_deletion_request (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    codeset_id       uuid        NOT NULL REFERENCES catalog.code_set(id) ON DELETE CASCADE,
    requested_by     uuid        NOT NULL,            -- om_user_id Author'а
    reason           text        NOT NULL CHECK (length(reason) BETWEEN 10 AND 2000),
    status           text        NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED')),
    decided_by       uuid,                            -- om_user_id Admin'а/Author'а
    decision_comment text        CHECK (decision_comment IS NULL OR length(decision_comment) <= 2000),
    created_at       timestamptz NOT NULL DEFAULT now(),
    decided_at       timestamptz,
    CHECK (decided_at IS NULL = (status = 'PENDING')),
    CHECK (status <> 'REJECTED' OR decision_comment IS NOT NULL)
);

-- Один PENDING на codeset одновременно
CREATE UNIQUE INDEX cs_del_req_one_pending_per_codeset_ix
    ON catalog.code_set_deletion_request(codeset_id)
    WHERE status = 'PENDING';

CREATE INDEX cs_del_req_status_ix       ON catalog.code_set_deletion_request(status);
CREATE INDEX cs_del_req_requested_by_ix ON catalog.code_set_deletion_request(requested_by);
```

## Контракт REST

### Author-facing (RDM_AUTHOR, RDM_ADMIN)

```
POST /api/v1/codesets/{codesetId}/deletion-requests
  Body: { "reason": "<10..2000 chars>" }
  → 201 { "id": "<uuid>", "status": "PENDING", ... }
  → 404 если codeset не найден
  → 409 если уже есть PENDING на этот codeset
  → 422 если reason слишком короткий/длинный или codeset уже deleted_at

GET  /api/v1/deletion-requests/my
  → 200 [ {id, codeset_id, codeset_name, reason, status, created_at, ...} ]

POST /api/v1/deletion-requests/{id}:cancel
  → 204
  → 403 если не свой запрос
  → 409 если статус != PENDING
```

### Admin-facing (RDM_ADMIN)

```
GET  /api/v1/admin/deletion-requests?status=PENDING
  → 200 [ {id, codeset_id, codeset_name, domain_name, reason, requested_by, requested_by_username, created_at} ]

POST /api/v1/admin/deletion-requests/{id}:approve
  Body: { "decision_comment": "<optional, ≤2000>", "force_archive": false }
  → 204 (CodeSet soft-deleted)
  → 409 если статус != PENDING
  → 409 если есть PUBLISHED-версии и force_archive=false
        (тот же guard, что в AdminCodeSetService.delete)
  → 409 если requested_by == decided_by (self-approval)

POST /api/v1/admin/deletion-requests/{id}:reject
  Body: { "decision_comment": "<required, 10..2000>" }
  → 204
  → 409 если статус != PENDING
  → 400 если decision_comment пустой
```

## Контракт UI

### Author flow

1. На `CodeSetPage` (`/codesets/{id}`) для пользователя с ролью
   `RDM_AUTHOR` или `RDM_ADMIN`, если CodeSet не soft-deleted и нет
   текущей PENDING-заявки — отображается кнопка **«Запросить удаление»** (danger).
2. Click → AntD Modal с обязательной textarea `reason` (min 10, max 2000),
   кнопка Submit disabled до валидной длины.
3. Submit → toast «Заявка отправлена», кнопка заменяется на бейдж
   **«Заявка на удаление: PENDING»** с кнопкой `Cancel` (только если
   текущий пользователь = `requested_by`).

### Admin queue

1. В sidebar admin-меню новый пункт **«Заявки на удаление»** с бейджем
   количества PENDING (poll каждые 30s).
2. Страница `/admin/deletion-requests` — таблица с фильтром по статусу
   (default PENDING). Колонки: Domain, CodeSet, Reason (truncated),
   Requested by, Requested at, Actions.
3. Actions:
   - `Approve` → confirm-modal. Если `has_published_versions` —
     дополнительный checkbox `force archive (despite published)`.
   - `Reject` → modal с обязательным `decision_comment`.
4. После решения — row уходит из PENDING-фильтра; история доступна
   через смену фильтра на APPROVED/REJECTED.

### Author "Мои заявки"

Расширение `MyTasksPage` секцией «Мои заявки на удаление» (или новая
страница `/my-deletion-requests`). Из этой секции тоже доступен `Cancel`.

## Инварианты и взаимодействия

- **Soft-delete vs hard-delete**: approve всегда вызывает
  `AdminCodeSetService.delete(codesetId, forceArchive=req.forceArchive)`
  — это **soft** (`deleted_at = now()`). PUBLISHED-версии остаются
  доступны через distribution API (для regulator IFRS9 reproducibility).
- **Идемпотентность approve**: если CodeSet уже soft-deleted (например,
  параллельно admin удалил руками через `/admin/codesets/{id}` E18) —
  approve переводит заявку в APPROVED без второго `softDelete` call.
- **CASCADE deletion request → code_set**: FK с `ON DELETE CASCADE`.
  Если CodeSet когда-нибудь hard-deleted (не сейчас, но потенциально
  через manual SQL) — заявки уйдут вместе с ним. Soft-delete (deleted_at)
  не запускает CASCADE — заявки переживают.
- **Self-approval prevention**: проверяется в Service. Admin, который
  по совместительству является Author заявки, не может её approve.
  Должен поверить другому admin'у.
- **Audit**: каждый submit/cancel/approve/reject пишет `log.info`.
  Append-only audit-таблица (E10) подцепит автоматически через
  EventBus, если нужно — можно добавить hook отдельным slice.
- **Permissions cache**: approve меняет `code_set.deleted_at` —
  catalog read-port это видит через свои queries (`WHERE deleted_at IS NULL`),
  отдельной invalidation не нужно.

## Smoke plan

1. **Submit, no PUBLISHED.**
   - Author создаёт CodeSet `test_e22` в домене Risk.
   - На `/codesets/<id>` появляется кнопка «Запросить удаление».
   - Click → modal → ввести reason ≥10 chars → Submit.
   - Toast «Заявка отправлена», кнопка заменена на бейдж «PENDING».

2. **Admin queue.**
   - Залогиниться Admin'ом. В sidebar бейдж «1». Клик → таблица с заявкой.
   - Click `Approve` → confirm. Поскольку `has_published_versions=false`,
     checkbox `force archive` скрыт. → Confirm.
   - Toast «CodeSet удалён». На `/catalog` `test_e22` больше не виден.

3. **Re-submit на удалённом — 422.**
   - Author вручную идёт на `/codesets/<deleted-id>` → CodeSet 404 в UI,
     кнопки нет.

4. **Reject path.**
   - Author создаёт `test_e22_v2`, submits.
   - Admin → Reject без комментария → ошибка валидации.
     С коммом `«CodeSet используется ETL-пайплайном XYZ»` → подтверждение.
   - Author в `/my-deletion-requests` видит REJECTED + comment.

5. **Cancel.**
   - Author создаёт `test_e22_v3`, submits.
   - Без admin-решения, идёт в `/my-deletion-requests`, Cancel → подтверждение.
   - В Admin queue заявка исчезает (filter=PENDING).

6. **Дубль PENDING — 409.**
   - Author создаёт `test_e22_v4`, submits. Открывает другую вкладку,
     пробует submit повторно → 409 «уже есть PENDING».

7. **Approve с PUBLISHED — 409 без force.**
   - Author публикует `test_e22_v5` v0.1.0 (4-eyes), потом submits delete.
   - Admin → Approve без `force_archive` → 409.
   - Approve с `force_archive=true` → success, CodeSet soft-deleted,
     PUBLISHED-версии остаются в БД (видимы по прямому `?version=`).

## Дальше (Slice B/C)

- **B1 — OM Owner в цепочке.** Заявка идёт сначала OM-Owner'у CodeSet'а
  (из `rdm_asset_ownership`), затем Admin'у. Аналогично BR-21 адресной
  маршрутизации согласования.
- **B2 — Email/Slack notification** Admin'у при новом PENDING (через
  Outbound webhook'и, E9). Сейчас Admin узнаёт сам.
- **C1 — Undelete admin-action**: восстановление soft-deleted CodeSet.
  Полезно для случая «approve по ошибке» — но workflow того же типа,
  с собственной таблицей `restoration_request`.
- **C2 — Auto-decline пожилых PENDING**: заявки старше 30 дней
  автоматически переводятся в CANCELLED с пометкой `auto-cancelled
  by stale-policy`.

## Открытые вопросы

- **Что если Author уволился и его om_user_id больше не валидный?**
  Сейчас PENDING заявка остаётся. UI «Мои заявки» у него никогда не
  откроется (нет логина), Admin видит её в очереди. Approve работает
  (decided_by — действующий Admin). Cancel невозможен — никто не
  отзовёт. Решение: Admin может Reject с комментарием «Author
  уволился». Никакого специального handling не делаем.
- **Audit-payload reason**: пишем reason в `log.info`. Если reason
  содержит чувствительные данные (название клиента) — это попадает
  в логи. Решение: документировать в UI hint'е, что reason пишется
  в audit; рассчитываем на здравый смысл Author'ов.
- **Reason — markdown или plain?** Текущий MVP — plain text. UI
  рендерит `<pre>` или `<Typography.Paragraph>` без markdown-parser'а.
