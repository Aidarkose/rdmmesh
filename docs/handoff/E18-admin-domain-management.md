# Handoff — Эпик E18 (Admin Domain Management: CRUD + OM dual-mastership)

> **Аудитория.** AI-агенты/инженеры после E18 foundation-PR. Контекст —
> [`SPEC.md`](../../SPEC.md) §2.4 (OM↔RDM), [`docs/adr/0011-domain-dual-mastership.md`](../adr/0011-domain-dual-mastership.md)
> (supersedes часть ADR-008), [`E1-foundation.md`](E1-foundation.md) §1.3 (Flyway),
> [`E7-ownership.md`](E7-ownership.md) (OM webhook receiver), [`E11.2c-ui-admin.md`](E11.2c-ui-admin.md)
> (UI admin раздел).
>
> **Дата.** 2026-05-22.
> **Состояние.** Закрыты **E18.1 foundation + E18.2 + E18.4 + E18.5 + E18.6**
> (backend + UI). E18.1: миграции V012/V013/V014 catalog, V063/V064 ownership,
> V100 admin; ADR-0011. E18.2/4/5/6: модуль `rdmmesh-admin` наполнен (DAO+service+
> resource), REST под `/api/v1/admin/...`, UI (страница Admin→Domains, ownership-
> панель на DomainPage, rename/delete на CodeSetPage, admin-секция в «Мои задачи»).
> **Smoke зелёный end-to-end** (dev-admin токен): create RDM-домен (master=RDM),
> rename, user-search, assign OWNER/STEWARD (origin=RDM), pin toggle, soft-delete,
> RBAC (author→403), codeset rename/delete routing (404 на несуществующем).
> UI `npm run build` + `tsc -b` зелёные.
> **Что НЕ сделано:** E18.3 (webhook receiver upgrade — генерация resolution_task
> при коллизиях; сейчас admin.resolution_task пуст и /admin/tasks/my отдаёт [])
> и E18.7 (conflict-log viewer). Также: DELETE домена не чистит orphan
> ownership-строки (asset_id без FK на domain) — мелкий долг, см. §6.

---

## 0. TL;DR

- **ADR-0010 → -0011 переход:** OM больше не единственный мастер по доменам.
  Per-row provenance: `catalog.domain.master ∈ {OM, RDM, LINKED}`,
  `ownership.rdm_asset_ownership.origin ∈ {OM, RDM}` + `pinned_local`.
- **Матчинг ТОЛЬКО по идентификаторам** (`om_domain_id` UUID). Имя — только hint
  в UI Linkage кандидатов. Никогда не auto-match по name.
- **Очередь admin-задач** — `admin.resolution_task` (отдельная схема, отдельный
  bounded context `rdmmesh-admin`). НЕ объединяется с `workflow.approval_task`
  (E5/BR-21). UI «Мои задачи» рендерит две независимые секции.
- **Backward compat соблюдён:** все существующие ряды получают
  `master='OM'`, `origin='OM'`, `pinned_local=false` через default'ы.
  Поведение до E18 (pure OM mirror) сохраняется ровно.

---

## 1. Что сделано в E18.1 (foundation)

### 1.1. Миграции (Flyway)

| Файл | Что |
|---|---|
| `catalog/V012__domain_dual_master.sql` | ALTER `catalog.domain`: relax `om_domain_id NOT NULL`, +master/local_overrides/external_refs/last_om_sync_at/deleted_in_om_at + CHECK `(master, om_domain_id)` consistency + два partial-индекса по master |
| `catalog/V013__domain_conflict_log.sql` | новая `catalog.domain_conflict_log` — field-level audit расхождений OM↔RDM (append-only по семантике, INSERT-only GRANT) |
| `catalog/V014__codeset_aliases.sql` | ALTER `catalog.code_set` ADD `aliases jsonb DEFAULT '[]'` + CHECK isArray + GIN-индекс. Для E18.5 rename без потери связи с ingestion-коннектором |
| `ownership/V063__ownership_origin_pinned.sql` | ALTER `ownership.rdm_asset_ownership`: +origin/pinned_local/assigned_by_user_id/superseded_at + три partial-индекса (active, RDM-origin, pinned) |
| `ownership/V064__domain_role_directory_origin.sql` | расширение CHECK `source` значением 'RDM_ADMIN_LOCAL' (через DROP CONSTRAINT + ADD); partial-индекс admin-локальных. Меняет семантику reload: было TRUNCATE+INSERT, стало `DELETE WHERE source <> 'RDM_ADMIN_LOCAL'; INSERT` |
| `admin/V100__admin_init.sql` | новая schema `admin` + `admin.resolution_task` (task_type/source_event_id/payload/status/...) + три индекса (PENDING hot path, type+status, related_domain) |

**Глобальная нумерация Flyway** (требование E1 §2): V012, V013, V014, V063, V064, V100 — не пересекаются ни с одной существующей версией (наивысшая была V074 в audit).

### 1.2. Каркас модуля `rdmmesh-admin`

```
rdmmesh-admin/
├── pom.xml                          (deps: rdmmesh-api, jersey, client, auth, jdbi3)
└── src/{main,test}/java/.gitkeep    (пустой src, наполнение — E18.2+)
```

Зарегистрирован в:
- `pom.xml` (parent) — `<modules>` после `rdmmesh-audit`, перед `rdmmesh-app`.
- `rdmmesh-app/pom.xml` — новой `<dependency>` после `rdmmesh-audit`.

### 1.3. Flyway config

- `rdmmesh-app/src/main/resources/config.yml` (dev): добавлено `admin` в schemas и `classpath:db/migration/admin` в locations.
- `rdmmesh-app/src/main/resources/config-prod.yml`: то же самое.
- `RdmmeshConfiguration.java` FlywayConfig — добавлены дефолты для `admin` (на случай конфига без явного списка).

### 1.4. ADR-0011

`docs/adr/0011-domain-dual-mastership.md` — supersedes часть ADR-008. SPEC §4.3 НЕ редактирован в этой раздаче (это V2-документ, обновляется отдельным PR в синхроне с merge feature-ветки).

---

## 2. Модель и инварианты — что должен знать следующий агент

### 2.1. `catalog.domain.master` decision tree

```
domain создаётся OM-webhook'ом (ENTITY_CREATED, новый om_domain_id):
  → master='OM', om_domain_id NOT NULL, local_overrides={}

domain создаётся admin'ом в UI (POST /admin/domains, без om_domain_id):
  → master='RDM',    om_domain_id IS NULL, external_refs={}

domain создаётся admin'ом со ссылкой на OM (POST /admin/domains, om_domain_id=...):
  → master='LINKED', om_domain_id NOT NULL (валидируется через OM API!)

admin переводит RDM-локальный в LINKED (POST /admin/domains/{id}:link-to-om):
  master='RDM' → 'LINKED', om_domain_id ставится атомарно

admin откатывает LINKED → RDM (POST /admin/domains/{id}:unlink-from-om):
  master='LINKED' → 'RDM', om_domain_id → NULL,
  предыдущее значение пишется в external_refs.former_om для аудита
```

CHECK constraint `domain_master_id_consistency` гарантирует инвариант.
**Никогда не пишите UPDATE catalog.domain SET master=..., om_domain_id=...
без согласованной правки обоих полей** — иначе CHECK провалит транзакцию.

### 2.2. `ownership.rdm_asset_ownership.origin` decision tree

```
webhook ENTITY_UPDATED.owners +ivanov:
  → INSERT (asset_id, role, om_user_id=ivanov, origin='OM', source_event_id=...)
  (или UPDATE если уже есть с origin='OM' — обновляем assigned_at)

admin POST /admin/domains/{id}/ownership {om_user_id, role}:
  → INSERT (..., origin='RDM', assigned_by_user_id=current_admin)

webhook ENTITY_UPDATED.owners -ivanov:
  если ряд (..., origin='OM') → UPDATE superseded_at=NOW(); INSERT в conflict_log;
  если ряд (..., origin='RDM') → НЕ ТРОГАЕМ; INSERT admin.resolution_task
                                  (task_type='OWNERSHIP_OM_REMOVAL_CONFLICT', payload содержит детали);
  если ряд (..., pinned_local=true) → НЕ ТРОГАЕМ; INSERT conflict_log resolution='SKIPPED'.

admin DELETE /admin/ownership/{id}:
  → UPDATE superseded_at=NOW(); audit log событие
  (физически не удаляем — для возможности undo и для compliance)
```

### 2.3. Webhook receiver — изменение алгоритма (E18.3)

**Текущий (до E18.3, не реализовано):** webhook ищет domain по `om_domain_id`. Если не найден → INSERT новой записи как `master='OM'`. Это работает только пока RDM-локальных доменов нет.

**После E18.3 (TODO):**

```pseudo
on ENTITY_CREATED/UPDATED entity=domain:
  row = SELECT WHERE om_domain_id = payload.om_domain_id;
  if row found:
    if row.master = 'OM':       full upsert as before
    if row.master = 'LINKED':   field-level (см. §4 матрица)
    if row.master = 'RDM':      ImpossibleStateException (UUID коллизия — лог + audit)
  else:
    candidates = SELECT WHERE master='RDM'
                   AND deleted_at IS NULL
                   AND (lower(name) = lower(payload.name)
                        OR similarity(name, payload.name) > 0.85);
    if candidates is empty:
      INSERT new (master='OM', om_domain_id=...)    # нормальный кейс
    else:
      INSERT admin.resolution_task (task_type='DOMAIN_LINKAGE',
                                     source_event_id=...,
                                     payload={om_payload, suggested_local_id, match_score, candidates})
      # НЕ создаём mirror автоматически — ждём admin'а
```

### 2.4. `domain_role_directory.source='RDM_ADMIN_LOCAL'` и reload

`ApproverDirectoryReloadService` (E17, `rdmmesh-ownership`) сейчас делает TRUNCATE+INSERT. **TODO для E18.4:** заменить на

```sql
DELETE FROM ownership.domain_role_directory WHERE source <> 'RDM_ADMIN_LOCAL';
INSERT INTO ownership.domain_role_directory (..., source='LOCAL_SEED'|'OM_GENERATED') VALUES (...);
```

в одной транзакции. Admin'ские записи (source='RDM_ADMIN_LOCAL') переживают reload. **Тест-кейс на это обязателен** — без него легко регрессировать.

---

## 3. REST API контракт (целевой, реализация — E18.2+)

```
# Domain CRUD (E18.2)
POST   /api/v1/admin/domains
       { name, code, description, label_ru, label_en, om_domain_id?, external_refs? }
       om_domain_id указан → master='LINKED', валидация через OM API (синхронный lookup)
       om_domain_id не указан → master='RDM'
       RBAC: RDM_ADMIN

PATCH  /api/v1/admin/domains/{id}
       { description?, label_ru?, label_en?, tags? }
       master='OM' → 403
       master='RDM' → любые поля выше
       master='LINKED' → пишется в local_overrides (RDM перебивает OM)
       RBAC: RDM_ADMIN

PATCH  /api/v1/admin/domains/{id}:rename
       { new_name }
       master='OM' → 403 (rename доступен только в OM)
       master='LINKED' → 403 (name = OM-mastered field)
       master='RDM' → переименование, valid regex ^[a-z][a-z0-9_]{0,63}$
       RBAC: RDM_ADMIN

DELETE /api/v1/admin/domains/{id}
       предохранитель: count(code_set WHERE domain_id=$1 AND deleted_at IS NULL) == 0
       master='OM' → 403 (используйте OM)
       RBAC: RDM_ADMIN

POST   /api/v1/admin/domains/{id}:link-to-om
       { om_domain_id }
       RDM → LINKED, валидирует om_domain_id через OM API
       RBAC: RDM_ADMIN

POST   /api/v1/admin/domains/{id}:unlink-from-om
       LINKED → RDM, om_domain_id очищается, external_refs.former_om сохраняет UUID
       RBAC: RDM_ADMIN

# Ownership admin (E18.4)
POST   /api/v1/admin/domains/{id}/ownership
       { om_user_id, role }
       origin='RDM', assigned_by_user_id=current
       RBAC: RDM_ADMIN

PATCH  /api/v1/admin/ownership/{id}
       { pinned_local? }
       Только этот флаг можно патчить (origin/role/user — пересоздать через DELETE+POST)
       RBAC: RDM_ADMIN

DELETE /api/v1/admin/ownership/{id}
       origin='OM' → 200 с warning: «эта запись пришла из OM, удалится но может
                       вернуться следующим webhook'ом. Используйте pinned_local
                       чтобы защитить»
       origin='RDM' → 200 чисто (admin удаляет своё)
       Soft-delete: UPDATE superseded_at; физически не удаляется
       RBAC: RDM_ADMIN

# CodeSet admin (E18.5)
PATCH  /api/v1/admin/codesets/{id}:rename
       { new_name, keep_alias_for_ingestion?: bool=true }
       new_name → catalog.code_set.name; если keep_alias=true → старое имя пушится
       в aliases JSONB-массив (одной транзакцией)
       RBAC: RDM_ADMIN

DELETE /api/v1/admin/codesets/{id}
       предохранитель: нет PUBLISHED версий (или флаг force_archive=true)
       Soft-delete catalog.code_set.deleted_at
       RBAC: RDM_ADMIN

# Admin tasks (E18.6) — НЕ ТРОГАЕТ /tasks/my
GET    /api/v1/admin/tasks/my
       (RDM_ADMIN only — для не-admin'а 403, UI не делает запрос)
       SELECT WHERE status='PENDING' с пагинацией
       Response: AdminResolutionTask[] (flat array, аналогично /tasks/my)

POST   /api/v1/admin/tasks/{id}:resolve
       { action: 'LINK'|'CREATE_NEW'|'MERGE'|'IGNORE'|'CONVERT_TO_RDM_LOCAL'
                 |'SOFT_DELETE'|'CONFIRM_REMOVAL'|'KEEP_LOCAL_PIN'|'SWITCH_ORIGIN_TO_OM',
         params?: {target_local_domain_id?, ...} }
       Атомарно: UPDATE admin.resolution_task (status='RESOLVED', ...)
                 + соответствующие side-effect'ы в catalog/ownership
                 + audit event
       SELECT ... FOR UPDATE SKIP LOCKED для защиты от двойного резолва
       RBAC: RDM_ADMIN

# Conflict log (E18.7)
GET    /api/v1/admin/conflict-log?domain_id=&from=&to=&resolution=
       Read-only пагинированный список catalog.domain_conflict_log
       RBAC: RDM_ADMIN

# User lookup (E18.4)
GET    /api/v1/admin/users/search?q=...&source=local|om
       source=local: SELECT FROM identity.rdm_user_mapping WHERE username/display ILIKE
       source=om: проксирование в OM REST API, lazy-cache в rdm_user_mapping
       RBAC: RDM_ADMIN
```

### Что НЕ меняется

- `GET /api/v1/tasks/my` — **контракт неизменен**, тот же `ApprovalTaskDto[]` от `MyTasksResource` (E5/BR-21). Не трогаем ни форму response, ни Java DTO, ни UI consumer.
- Существующий `POST /api/v1/domains` bootstrap-эндпоинт (SPEC §2.4) — оставляем как есть до feature-полного E18.2, потом депрекируем feature-флагом.

---

## 4. Матрица разрешения конфликтов (для E18.3 webhook receiver)

### Сценарий A — OM webhook на domain, у нас RDM-локальный с похожим именем

См. §2.3 псевдокод выше. Создаётся `admin.resolution_task` (`task_type='DOMAIN_LINKAGE'`).

### Сценарий B — field-level update LINKED-домена

| Поле | master='OM' | master='LINKED' | master='RDM' |
|---|---|---|---|
| `name` | overwrite | **OM wins** (запрет в RDM UI); запись в conflict_log если local_overrides.name случайно стоит | n/a (нет OM-event'а) |
| `parent_domain_id` (иерархия) | overwrite | **OM wins** | n/a |
| `description` | overwrite | если `local_overrides.description` есть → **RDM wins**, OM-значение → conflict_log; иначе overwrite | n/a |
| `label_ru` | overwrite | то же, что description | n/a |
| `label_en` | overwrite | то же | n/a |
| `tags` | overwrite | merge (union OM ∪ local_overrides.tags если есть) | n/a |
| `display_name` | overwrite | то же, что description | n/a |
| owners/experts/reviewers | см. сценарий C ниже | то же | n/a |

### Сценарий C — ownership delta

```
OM webhook: ENTITY_UPDATED owners=[ivanov,petrov] (было [ivanov]):
  → INSERT для petrov как (origin='OM') — обычный path
  → existing ivanov не трогаем (origin='OM', уже актуален)

OM webhook: ENTITY_UPDATED owners=[petrov] (было [ivanov,petrov]):
  ivanov-row.origin='OM' → UPDATE superseded_at=NOW(), conflict_log resolution='OM_WINS'
  ivanov-row.origin='RDM' → НЕ ТРОГАЕМ; admin.resolution_task
                              (task_type='OWNERSHIP_OM_REMOVAL_CONFLICT',
                               payload={asset_id, role, om_user_id=ivanov,
                                        proposed_state='REMOVED'})
  ivanov-row.pinned_local=true → НЕ ТРОГАЕМ; conflict_log resolution='SKIPPED'
```

### Сценарий D — OM удаляет LINKED-домен

```
OM webhook: ENTITY_DELETED entity=domain om_domain_id=<x>:
  row = SELECT WHERE om_domain_id=<x>
  if row.master='OM' → UPDATE deleted_at=NOW() (soft-delete как до E18)
  if row.master='LINKED':
    UPDATE deleted_in_om_at=NOW();
    INSERT admin.resolution_task
      (task_type='DOMAIN_DELETED_IN_OM', related_domain_id=row.id,
       payload={om_payload, dependent_codeset_ids: [...]})
    — domain в RDM остаётся живым, codeset'ы продолжают работать
    — admin вручную выбирает: CONVERT_TO_RDM_LOCAL (master='RDM' + om_domain_id→NULL)
                                  или SOFT_DELETE (catalog.domain.deleted_at)
```

---

## 5. UI «Мои задачи» — расширение (E18.6)

`rdmmesh-ui/src/pages/MyTasks.tsx` (или эквивалент — путь зависит от того, как назвали в E11):

- Две независимые React Query queries:
  - `useQuery({ queryKey: ['tasks','my'], queryFn: endpoints.myTasks })` — уже есть.
  - `useQuery({ queryKey: ['admin','tasks','my'], queryFn: endpoints.adminTasksMy, enabled: hasRole('RDM_ADMIN') })` — новый.
- Если у пользователя нет роли `RDM_ADMIN` → admin-секция не рендерится и запрос не отправляется (`enabled: false`).
- Layout: две секции, заголовки, бейдж количества, карточки с кнопкой «Разрешить».
- На клик «Разрешить» — модалка resolveTask, разная per task_type:
  - `DOMAIN_LINKAGE` — список candidates, кнопки LINK/CREATE_NEW/MERGE/IGNORE.
  - `OWNERSHIP_OM_REMOVAL_CONFLICT` — детали tuple'ов, кнопки CONFIRM_REMOVAL/KEEP_LOCAL_PIN/SWITCH_ORIGIN_TO_OM.
  - `DOMAIN_DELETED_IN_OM` — список codeset'ов, кнопки CONVERT_TO_RDM_LOCAL/SOFT_DELETE.

UI типы (TS) — генерация через `rdmmesh-spec/codegen/typescript/` если решите формализовать payload через JSON Schema; сейчас можно оставить inline TS types в endpoints.ts.

---

## 6. Указатели на следующие подэпики

| Подэпик | Что | Зависит от |
|---|---|---|
| **E18.2** Domain admin CRUD | `rdmmesh-admin/src/main/java/.../AdminDomainResource.java` + DAO для catalog.domain (через **новый** `CatalogAdminPort` в `rdmmesh-api`) + UI screens | E18.1, E3 |
| **E18.3** Webhook receiver upgrade | `rdmmesh-ownership` — модификация `OmOwnershipWebhookResource` (см. псевдокод §2.3); генерация `admin.resolution_task` через event-bus + подписчик в `rdmmesh-admin` | E18.1, E7 |
| **E18.4** Ownership admin assignment | `AdminOwnershipResource` + расширение `OwnershipPort` методами `assignLocal()`/`pinLocal()` + UI экраны | E18.1, E7 |
| **E18.5** CodeSet rename/delete | `AdminCodeSetResource` + новый `CodeSetAdminPort` + UI rename modal + договор с `om-rdmmesh-source` коннектором об aliases | E18.1, E3, E12 |
| **E18.6** My Tasks admin section | `AdminTaskResource` (GET /admin/tasks/my, POST /admin/tasks/{id}:resolve) + UI расширение страницы My Tasks | E18.1 + любой из 18.2-18.5 (нужны actual задачи) |
| **E18.7** Conflict log viewer | `AdminConflictLogResource` (read-only GET с фильтрами) + UI экран | E18.1 |

**MVP-cut для первой production-раскатки:** E18.1 (этот PR) + **E18.2 + E18.4 + E18.6**. E18.3 (webhook upgrade) можно вторым PR — до его выхода уже-известные om_domain_id будут продолжать работать как раньше, а коллизий по имени в bootstrap-периоде просто нет (RDM-локальных доменов пока 0).

---

## 7. Открытые вопросы / решения, требующие подтверждения

Уже решено на этапе дизайна (см. ADR-0011 §«What we don't do»):

- ✅ Одна глобальная роль `RDM_ADMIN`, без per-domain admin'ов.
- ✅ Merge доменов — без 4-eyes, single-actor с усиленным UI confirm.
- ✅ `pinned_local` присутствует как отдельный флаг.
- ✅ Конфликты — в существующую вкладку «Мои задачи», без отдельного Linkage Inbox экрана.
- ✅ Без auto-ignore старых pending-задач (живут вечно).

Оставшиеся **обязательно требующие подтверждения перед E18.3:**

1. **OM API endpoint для валидации `om_domain_id` при `:link-to-om`.** Нужен синхронный GET в OM REST API. URL — что-то вроде `GET ${RDM_OM_BASE_URL}/api/v1/domains/{id}`. Подтвердить:
   - Точный путь и формат response.
   - Какой bot-токен использует RDM (тот же `RDM_OM_BOT_TOKEN`, что и для lazy user lookup, или отдельный).
   - Timeout: дефолт 5s достаточен?
2. **Имя header'а для HMAC OM-webhook'а** — SPEC §2.4 говорит `X-OM-Signature`. Подтвердить, что E7 действительно его слушает (не переименовали при реализации).
3. **Politika для случая RDM-локальный домен с именем, совпадающим с уже существующим OM-доменом.** При POST /admin/domains admin вводит name='risk', а в OM уже есть domain 'risk' (mapped в RDM как master='OM'). Варианты:
   - (a) UNIQUE constraint на `name` (он сейчас есть на catalog.domain.name) → admin получит 409 при попытке создать с тем же именем. **Текущее поведение.**
   - (b) Разрешить, имена не должны совпадать только в пределах одного master-класса.
   
   Лично рекомендую оставить (a) — UNIQUE на name прост и понятен. Если admin хочет «оба» — пусть переименует одного.

---

## 8. Smoke test, который должен пройти после этой раздачи

```bash
cd ~/projects/rdmmesh
docker compose -f docker/docker-compose.yml down -v        # сбросить старый volume
make up                                                     # поднять Postgres + сервис
curl -s http://localhost:8081/healthcheck | python3 -c \
  "import sys, json; d=json.load(sys.stdin); print(d)"     # должно быть 200, три зелёных

docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -c "\dn"
  # должно быть 9 schemas: rdmmesh_meta, catalog, authoring, workflow,
  # publishing, identity, ownership, audit, admin

docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -c \
  "\d catalog.domain"
  # должны быть колонки: id, om_domain_id (NOT NULL DROPPED), name, ...,
  # master (text NOT NULL DEFAULT 'OM'), local_overrides jsonb, external_refs jsonb,
  # last_om_sync_at timestamptz, deleted_in_om_at timestamptz

docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -c \
  "\d ownership.rdm_asset_ownership"
  # должны быть колонки: ..., origin text DEFAULT 'OM', pinned_local boolean DEFAULT false,
  # assigned_by_user_id uuid, superseded_at timestamptz

docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -c \
  "\d admin.resolution_task"
  # вся таблица должна существовать

docker exec rdmmesh-postgres psql -U rdmmesh_admin -d rdmmesh -c \
  "SELECT version, description, success FROM rdmmesh_meta.flyway_schema_history ORDER BY installed_rank"
  # должны быть применены: V001, V010..V014, V020..V023, V030..V034, V040, V041,
  # V050, V060..V064, V070..V074, V100
```

---

## 9. Версия документа

- **0.1** — 2026-05-22. Создан после закрытия E18.1 (foundation). Автор предыдущей сессии: Claude Opus 4.7 (1M context).
- Будущие правки — in-place, обновлять версию и дату.
