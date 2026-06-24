# ADR-0011. Domain dual-mastership: OM and RDM admin co-author the domain catalog

- **Status.** Accepted (E18 design phase).
- **Date.** 2026-05-22.
- **Supersedes.** Часть ADR-008 (SPEC §4.3) — «OM — единственный мастер по доменам и ownership'у».
- **Reference.** SPEC §2.4, §3.5, §4.3 ADR-008; handoff [`E18-admin-domain-management.md`](../handoff/E18-admin-domain-management.md); ADR-0009 (workflow engines as ports — паттерн «несколько источников за абстракцией»).

## Context

ADR-008 (SPEC §4.3) зафиксировал OM как **единственный** источник истины по бизнес-доменам и ownership'у на data assets. Это работало, пока:

1. OM-каталог был стабильно заполнен (все нужные домены уже там).
2. RDM не использовался в новых доменах, которых ещё не было в OM.
3. У admin'а не возникало необходимости вручную корректировать ownership-назначения (например, восстановить случайно удалённое в OM).

После полугода пилотной эксплуатации обнаружились три рекуррентных сценария, которые ADR-008 не поддерживает:

- **A. Новый домен инициируется в RDM.** Бизнес-домен хочет завести справочник раньше, чем data-governance оформила Domain в OM. По ADR-008 это блокировано: domain должен прийти из OM. Bootstrap-REST из SPEC §2.4 — технический костыль, не продуктовая возможность.
- **B. Admin вручную назначает ownership.** При отсутствующем или ещё не настроенном webhook'е OM admin'у нужно временно назначить steward'а/owner'а локально, чтобы 4-eyes согласование работало. SPEC §2.4 это не предусматривал.
- **C. OM-webhook откатывает локальную правку.** Admin исправил ownership-назначение → следующий webhook восстановил «правильное» (с точки зрения OM) состояние, потеряв правку. Нет ни «pin», ни уведомления — расхождение происходит молча.

Альтернативы, которые рассматривались:

- **(a) Оставить ADR-008 как есть, расширить bootstrap-REST.** Приводит к продуктизации костыля без явного контракта; конфликты разрешаются ad hoc.
- **(b) Полностью отменить OM-mastership, RDM становится мастером.** Ломает корпоративное governance (OM остаётся хабом DG); ведёт к двум источникам истины с непредсказуемым drift'ом.
- **(c) Per-row provenance, mastership per record.** Каждый ряд явно помечен «кто его автор» — OM, RDM или гибрид. Конфликты разрешаются по детерминированной матрице. Выбран этот путь.

## Decision

**Conditional mastership с явным provenance.** Не «OM мастер» и не «RDM мастер», а «mastership определяется per row».

### 1. `catalog.domain.master ∈ {OM, RDM, LINKED}`

- `OM` — pure mirror: ряд создан OM-webhook'ом, RDM не редактирует, om-webhook авторитетен.
- `RDM` — создан admin'ом в UI RDM, нет `om_domain_id`, OM webhook'у нечего синхронизировать (нет матча по UUID).
- `LINKED` — RDM-локальный домен явно слинкован admin'ом с OM-доменом. Field-level правила: `name` / иерархия — OM wins; `description` / `label_*` / `tags` — RDM может перебить через `local_overrides` JSONB.

### 2. `ownership.rdm_asset_ownership.origin ∈ {OM, RDM}` + `pinned_local boolean`

`origin` — кто создал ряд: webhook OM или admin локально. OM-webhook **не трогает** строки с `origin='RDM'`; вместо этого — генерация admin task в `admin.resolution_task`.

`pinned_local` — ортогональный флаг: даже OM-origin ряд защищён от воскрешения/удаления webhook'ом, расхождение пишется в `catalog.domain_conflict_log` как `SKIPPED`.

### 3. Матчинг **только по идентификаторам**, никогда по имени

`om_domain_id` (UUID) — единственный автоматический ключ матча webhook → ряд. Имя (FQN) ненадёжно (rename в OM, коллизии siblings, локализованные display_name). Если по `om_domain_id` ряд не найден — webhook **не создаёт** дубликат автоматически, а кладёт `admin.resolution_task` (`task_type='DOMAIN_LINKAGE'`) с suggested_local-кандидатом по сходству имени.

Структура `external_refs JSONB` оставляет место для будущих систем (AD OU, LDAP group, и т.п.) без DDL.

### 4. Очередь конфликтов — `admin.resolution_task` отдельно от `workflow.approval_task`

Не объединяем с workflow approval task'ами (E5/BR-21). Разные state machines, разные RBAC, разные DTO. UI «Мои задачи» рендерит две независимые секции, бэкенд отдаёт через два независимых эндпоинта:

- `GET /api/v1/tasks/my` — без изменений, для любого authenticated; контракт E5/BR-21 неизменен.
- `GET /api/v1/admin/tasks/my` — новый, RDM_ADMIN only.

Это сохраняет ArchUnit-изоляцию: `rdmmesh-admin` не зависит от `rdmmesh-workflow`.

### 5. `domain_role_directory.source` расширяется значением `RDM_ADMIN_LOCAL`

Семантика OM-snapshot reload меняется с `TRUNCATE + INSERT` (V062) на `DELETE WHERE source <> 'RDM_ADMIN_LOCAL'; INSERT`. Admin-локальные записи переживают full-replace.

## Consequences

### Положительные

- **Сценарий A разблокирован.** RDM_ADMIN создаёт RDM-локальный домен, бизнес-домен заводит справочник, через UI можно позже слинковать с OM (одной кнопкой `:link-to-om`).
- **Сценарий B разблокирован.** Admin назначает ownership локально через `POST /admin/domains/{id}/ownership`; OM-webhook эти ряды не трогает (origin='RDM').
- **Сценарий C решён.** Расхождения OM↔RDM никогда не происходят молча: либо webhook применяет OM-значение и пишет в `conflict_log`, либо генерирует admin task в `resolution_task`.
- **OM остаётся governance-хабом** для своих собственных доменов; ADR-008 не отменяется полностью, а уточняется: «OM мастер там, где OM источник; RDM мастер там, где RDM источник; LINKED — гибрид с детерминированной матрицей».
- **Контракт `GET /tasks/my` не ломается** — существующее E5/BR-21 поведение steward/owner неизменно.

### Отрицательные

- **Сложнее ментальная модель.** Раньше «domain = OM-mirror». Теперь — три класса, две оси (`master` + `origin`), плюс `pinned_local`. Это документируется в E18 §4 (decision tables) и при первом ревью с DG-командой потребует разъяснения.
- **Больше состояний в БД** — два новых constraint'а на `catalog.domain` (master/id consistency) и три новых индекса (partial по master), плюс новые поля в `ownership` (origin/pinned_local/superseded_at).
- **Усиление admin-роли.** RDM_ADMIN теперь может создавать домены и переписывать ownership локально. Mitigation: каждое admin-действие в `audit.audit_log` через `EventBus`, регулярный review со стороны DG.
- **Дополнительная нагрузка на webhook receiver.** При каждом ENTITY_UPDATED для LINKED-домена нужно сверить с `local_overrides`, при ownership-update — проверить origin и pinned_local. Локальная JOIN-стоимость на ряд — пренебрежимо мала для текущих объёмов (SPEC §1.4: десятки–сотни доменов).

### Что НЕ делаем сейчас (и почему)

- **Push RDM-локальных доменов в OM** (RDM → OM API). Это требует OM-OAuth с правом на create-domain, политики «кто пушит» и flow «promote to OM». Отложено в V2. Сейчас RDM-локальные остаются локальными до явного `:link-to-om` (где admin указывает уже существующий `om_domain_id`).
- **Merge доменов с 4-eyes.** По решению пользователя (зафиксировано в обсуждении 2026-05-22) — merge остаётся single-actor операцией с усиленным UI confirm-диалогом, но без второго admin'а.
- **Auto-ignore старых pending-задач.** Pending-задачи живут вечно до admin-resolve; TTL не вводим.
- **Per-domain admin-роль.** Одна глобальная `RDM_ADMIN`; per-domain admin-роль не вводим до явного бизнес-требования.

## Implementation

См. handoff [`E18-admin-domain-management.md`](../handoff/E18-admin-domain-management.md):

- **E18.1** (миграции) — V012/V013/V014 catalog, V063/V064 ownership, V100 admin (закрыто этой PR).
- **E18.2** (Domain admin CRUD) — REST + UI экраны.
- **E18.3** (Webhook receiver upgrade) — ID-first lookup, генерация resolution_task'ов.
- **E18.4** (Ownership admin assignment) — REST + UI + pinned_local.
- **E18.5** (CodeSet rename/delete + aliases) — REST + UI + контракт с ingestion-коннектором.
- **E18.6** (My Tasks admin section) — расширение UI «Мои задачи» новой секцией.
- **E18.7** (Conflict log viewer) — read-only UI экран.

**MVP-cut:** E18.1 + E18.2 + E18.4 + E18.6.

## Revisions

- **2026-05-22 (v1).** Initial. После обсуждения с пользователем (BR-23..BR-25 в SPEC §2.5 — добавятся отдельным PR'ом одновременно с E18 implementation).
