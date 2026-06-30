# Handoff — Identity/Role redesign (objectGUID anchor) + операционные находки

> **Аудитория.** AI-агенты и инженеры, подключающиеся к `rdmmesh` после этой сессии.
> Документ самодостаточен: контекста предыдущей переписки у вас нет, всё нужное — здесь,
> в [`SPEC.md`](../../SPEC.md), [`E2-identity.md`](E2-identity.md) и в memory-файле
> `project_identity_role_redesign` (auto-memory).
>
> **Дата.** 2026-06-27 (Phase 1), обновлено 2026-06-30 (Phases 2–4 — см. §10 ниже).
> **Состояние (на 2026-06-30).** Phases **1, 2, 3, 4(A+B) и 4(C)** — реализованы, закоммичены,
> запушены (GitHub `Aidarkose/rdmmesh` + локальный GitLab `root/rdmmesh`) **и задеплоены**
> (rebuild+recreate, healthy). e2e пройдены (см. §10). **Редизайн завершён**: нативная OM
> Event Subscription авто-пропагирует изменения домена/owner из UI OM в rdmmesh (§10.6).
> **Свежему агенту: читать §10 — там актуальное состояние; §1–9 ниже — исходный контекст Phase 1.**
> **Ветка.** `feat/om-rdmmesh-sync`. Ключевые коммиты: Phase 1 `4f58f2b`/`6454e0c`;
> Phase 2 `beb8feb`+`3d1671c`; Phase 3 `bd6c8ff`+`a4bbf06`+`3a510ae`; Phase 4 `06b5aac`.

---

## 0. TL;DR за 60 секунд

1. Расследовали баг «у стьюарда/оунера не отображаются открытые approval-задачи». **Корень:**
   `om_user_id` в директории согласующих разошёлся с `om_user_id`, который пользователь
   получает при логине. Причина — provisional `om_user_id` выводился из **волатильного
   `keycloak_sub`** (`uuidv5(namespace, sub)`), а `sub` регенерируется при пере-импорте realm
   (dev-Keycloak на in-memory H2 без тома, без пиннинга id).
2. Решение, согласованное с пользователем: **пере-якорить идентичность на идемпотентный AD
   `objectGUID`** (claim `oid`), убрать provisional, сделать `om_user_id` nullable
   (viewer = NULL), оставить `om_user_id` как interop-ключ с OpenMetadata.
3. Ролевую модель свели к плоской: группы Keycloak только `RDM_STEWARD`/`RDM_OWNER`/`RDM_VIEWER`
   + `RDM_ADMIN` (операционная, вне DG-триады). Авторы = стьюарды.
4. План на 4 фазы. **Phase 1 (identity re-anchor + чистка ролей) сделана** — см. §4.
5. Попутно починили Debezium-пайплайн rdmmesh→Kafka→ecl (см. §5) и зафиксировали операционные
   факты dev-стека (порты, креды — §6).

---

## 1. Контекст: как работает идентичность (на момент ДО Phase 1)

- rdmmesh — OIDC relying party. Keycloak realm `bank`, broker к корпоративному AD (по SPEC §5.1,
  `docker/keycloak/realms/realm-bank.json`).
- `identity.rdm_user_mapping` связывает `keycloak_sub → om_user_id` (+username/email). Заполняется
  **лениво при первом логине** (`KeycloakIdentityPort.resolve()`), не при создании юзера в Keycloak.
- `om_user_id` брался из двух источников (**двойной источник — корень хрупкости**):
  - если OM доступен — реальный `User.id` из OM (`omClient.findUserIdByName(username)`);
  - иначе — **provisional `uuidv5(RDM_PROVISIONAL_NAMESPACE, keycloak_sub)`**.
- Две системы ролей (важно не путать):
  - **базовые функциональные** (`RDM_*`) — из claim `groups` JWT, гейтятся `@RolesAllowed`/`RoleAuthorizer`;
  - **asset-level DG-роли** (Owner/Steward/Expert/Approver на конкретном CodeSet) — из OM ownership,
    ключуются по `om_user_id` (`OwnershipPort`, `ownership.rdm_asset_ownership`).

### Почему `keycloak_sub` нестабилен в dev
- Keycloak в `docker/docker-compose.yml` запущен `start-dev` с **H2 in-memory**, том примонтирован
  только на каталог импорта (`/opt/keycloak/data/import`, read-only) — **персистентного тома нет**.
- В realm-json пользователям **не были заданы `id`** → при каждом импорте Keycloak генерил новый `sub`.
- ⇒ при пересоздании контейнера `sub` менялся, provisional `om_user_id` пересчитывался, а директория
  согласующих (`ownership.domain_role_directory`, `LOCAL_SEED`) хранила старое значение → mismatch.
- Подтверждено расчётом: `om_user_id == uuidv5(NS=c5b1a4e1-7c00-4e2c-9c8b-2c0c2c8a6f10, sub)`.

### Дополнительный риск целостности (актуально и сейчас для legacy-строк)
- На `om_user_id` **нет ни одного FK** (проверено по `pg_constraint`). История согласований
  (`workflow.workflow_transition.actor`, `workflow.approval_task.candidate_users/closed_by`, audit)
  хранит «голое» значение `om_user_id` без каскада. Любая смена `om_user_id` у пользователя
  (реконсиляция provisional→real ИЛИ смена `sub`) **осиротит** прошлые approvals и спрячет открытые
  задачи/историю при запросе по новому id. Phase 1 это устраняет на будущее (стабильный якорь,
  provisional убран), но **исторические provisional-строки остаются** — при необходимости
  мигрировать ссылки отдельной транзакцией.

---

## 2. Согласованная целевая модель (полная)

- **Якорь идентичности — AD `objectGUID`** (claim `oid`). В проде приходит из LDAP attribute mapper
  Keycloak; в dev — из per-user атрибута realm + protocol mapper. rdmmesh **не** интегрируется с AD
  напрямую — только Keycloak (брокер). `keycloak_sub` понижен до атрибута логина.
- **`om_user_id` остаётся** (нужен как interop-ключ с OM: ownership-вебхуки несут OM-id), но:
  - NULLABLE: **viewer = NULL** (read-only, истории не порождает; provisional удалён совсем);
  - резолвится по имени учётки через OM REST «**до первого успеха, потом заморозка**»;
  - наличие `om_user_id` = учётка имеет роль в OM.
- **Группы Keycloak (плоские):** `RDM_STEWARD`, `RDM_OWNER`, `RDM_VIEWER` (DG-триада) + `RDM_ADMIN`
  (операционная роль платформы, вне триады, все полномочия). Удалены `RDM_AUTHOR` (→ авторы=стьюарды),
  `RDM_AUDITOR` (→ доступ отдан `RDM_ADMIN`), `RDM_CONSUMER`, `RDM_SCHEMA_DESIGNER`. Default → `RDM_VIEWER`.
- **Доступ/иерархия (Phases 2–3):**
  - стьюард создаёт/редактирует драфт **любого** справочника независимо от иерархии доменов;
  - маршрут согласования строго `STEWARD → OWNER`;
  - owner аппрувит только справочники **своего домена и доменов ниже по иерархии** (subtree);
  - справочник создаётся только внутри домена; домены и их иерархия (домены/поддомены) — из OM.
- **Владение (per-asset остаётся) + bootstrap-цепочка:** при создании справочника default-владелец =
  владелец домена (в его отсутствии — владелец домена выше по иерархии), ему уходит на согласование.
  После публикации метаданные попадают в OM, там назначается per-asset владелец; последующие правки
  согласуются с ним, в его отсутствии — с owner'ом домена. Цепочка резолва:
  **per-asset owner → domain owner → ancestor domain owner**.
- **OM-интеграция нативная (Phase 4):** OM шлёт alert/notification (Event Subscription) → rdmmesh
  делает pull через родной OM REST API (домены + иерархия + связки owner/steward + per-asset owners).
  Заменяет текущий `LOCAL_SEED` `domain_role_directory`. См. memory `feedback_no_om_modifications`
  (работать с vanilla-OM, не патчить).

---

## 3. План на 4 фазы

| Фаза | Содержание | Статус |
|---|---|---|
| **1. Identity re-anchor + чистка ролей** | claim `oid`/objectGUID, ключ маппинга `object_guid`, `om_user_id` nullable, provisional удалён, группы→4, гейты `AUTHOR→STEWARD`/`AUDITOR→ADMIN` | **✅ сделано** (§4) |
| **2. Иерархия доменов** | `parent_om_domain_id` в зеркало `catalog.domain*`, дерево (recursive CTE), subtree-запросы, UI-дерево. Предпосылка для 3 | ⬜ |
| **3. Доменный fallback владения + routing + subtree-доступ** | цепочка per-asset→domain→ancestor, default-owner при создании, `STEWARD→OWNER`, owner-аппрув в пределах поддерева | ⬜ |
| **4. Нативная OM-интеграция** | Event Subscription (alert) → pull через OM REST: домены+иерархия+owner/steward+per-asset; привязка `om_user_id` | ⬜ |

Зависимости: 1 → фундамент; 2 перед 3; 4 логически последняя (наполняет 2–3 реальными OM-данными).

### Важные открытые вопросы для Phase 2+
- В зеркале домена (`catalog.domain*`) **нет колонки parent** — иерархию надо строить с нуля
  (комментарий «parent — OM wins» в `V012__domain_dual_master.sql` относится к conflict-resolution).
- Владение сейчас per-asset (`ownership.rdm_asset_ownership`) + `domain_role_directory` + `version_route` —
  доменный fallback надо вкрутить в approver-routing (`WorkflowService.candidatesFor`/`version_route`).

---

## 4. Phase 1 — что именно сделано (коммит `4f58f2b`, 25 файлов)

### 4.1. Keycloak realm (`docker/keycloak/realms/realm-bank.json`)
- группы → `RDM_STEWARD`/`RDM_OWNER`/`RDM_VIEWER`/`RDM_ADMIN`; `defaultGroups: [RDM_VIEWER]`.
- каждому пользователю задан **фиксированный `id`** (= прежний `sub` из БД, чтобы legacy-строки не
  разъехались ещё раз) и атрибут `objectGUID` (dev-значения вида `ad000000-...`).
- в обоих clients (`rdmmesh-backend`, `rdmmesh-ui`) добавлен protocol mapper
  `oidc-usermodel-attribute-mapper`: `user.attribute=objectGUID → claim oid`. В backend заодно
  добавлен `preferred_username`-mapper.
- переназначения: `dev-author → RDM_STEWARD`, `dev-auditor → RDM_ADMIN`.

### 4.2. Flyway-миграция `bootstrap/sql/migrations/identity/V051__reanchor_object_guid.sql`
- `+ object_guid uuid` → бэкофилл `gen_random_uuid()` для existing → `NOT NULL` + `UNIQUE`
  (стабильный якорь; на первом логине upsert по `username` перезапишет реальным `oid`).
- surrogate `id uuid DEFAULT gen_random_uuid()` → новый PK (вместо PK на `om_user_id`).
- `om_user_id` → `DROP NOT NULL` + `UNIQUE` (несколько NULL = viewer'ы).
- `keycloak_sub` → `DROP NOT NULL`, снят UNIQUE (обычный атрибут).
- Проверено: прогон в транзакции с ROLLBACK против живой БД — структура верна.

### 4.3. Java (rdmmesh-identity + app + ресурсы)
- `JwtValidator` (`.../internal/jwt/JwtValidator.java`): извлекает claim `oid` → `Resolved.objectGuid`
  (обязательный; ошибка если отсутствует/не-UUID).
- `KeycloakIdentityPort` (`.../internal/KeycloakIdentityPort.java`): кэш и поиск по `object_guid`;
  **provisional UUID удалён** (`provisionalUuid`/`toBytes`/`RDM_PROVISIONAL_NAMESPACE` снесены);
  viewer = `om_user_id NULL`; «резолв-до-первого-успеха» + однократный `bindOmUserId`.
- `UserMappingDao` (`.../internal/dao/UserMappingDao.java`): `findByObjectGuid`, `bindOmUserId`
  (UPDATE только при `om_user_id IS NULL`), upsert по `username` с `object_guid` и
  `COALESCE`-защитой `om_user_id` от затирания NULL'ом; `UserMappingRow` получил поле `objectGuid`.
- Config: `config.yml`, `config-prod.yml`, `KeycloakConfig.java` — `oid` добавлен в `requiredClaims`.

### 4.4. Авторизация
- `@RolesAllowed`: `RDM_AUTHOR`/`RDM_SCHEMA_DESIGNER` → `RDM_STEWARD` (catalog/authoring/admin/workflow),
  `RDM_AUDITOR` → `RDM_ADMIN` (audit). Инвариант: любой write-путь, использующий `principal.omUserId()`,
  закрыт от viewer'а (у viewer `om_user_id NULL`, но он и не в STEWARD/OWNER/ADMIN).
- `StateMachine.java` (workflow): submit-гейт `RDM_AUTHOR/RDM_ADMIN` → `RDM_STEWARD/RDM_ADMIN`.

### 4.5. Тесты
- `JwtValidatorTest`: `oid` в валидном токене + новый негативный `rejects_token_without_oid_claim`.
- workflow/IT тесты: роль submit `RDM_AUTHOR` → `RDM_STEWARD`, `RDM_CONSUMER` → `RDM_VIEWER`.

### 4.6. Верификация (в Docker `maven:3.9-eclipse-temurin-21`, локального mvn/java нет)
```bash
docker run --rm -v "$PWD":/workspace -w /workspace -v "$HOME/.m2":/root/.m2 \
  maven:3.9-eclipse-temurin-21 mvn -B -ntp -pl rdmmesh-identity,rdmmesh-workflow -am test
```
- `mvn compile` + `test-compile` — BUILD SUCCESS.
- unit: identity 11, workflow 50 — все зелёные (вкл. новые oid/steward-тесты).
- миграция V051 — dry-run OK.

### 4.7. НЕ сделано (следующий шаг)
**E2e-деплой:** пересборка образа `rdmmesh-service` (multi-stage maven build по `docker/Dockerfile`),
пересоздание контейнера `keycloak` (импорт нового realm — **сбросит in-memory состояние Keycloak**),
Flyway применит V051 на старте сервиса, затем smoke: `make kc-token` → проверить `oid` в токене и что
`/auth/me` отдаёт `objectGuid`, маппинг ключуется по `object_guid`.

---

## 5. Операционная находка: Debezium-пайплайн rdmmesh → Kafka → ecl

CDC-репликация справочников `rd_data` живёт **не** в репо rdmmesh (там только `wal_level=logical`),
а в проекте **`~/projects/event-bus`**. Коннекторы регистрируются в Kafka Connect (`eventbus-connect`,
REST на хосте **:8087**).

- Source: `event-bus/connect/connectors/rdmmesh-refdata-source.json` — Debezium Postgres,
  `database.hostname=rdmmesh-postgres`, `plugin=pgoutput`, `slot=debezium_rdmmesh`,
  `publication=dbz_rdmmesh_refdata`, schema `rd_data`, таблицы `*__current`, topic prefix `rdmmesh`.
- Sink: `ecl-refdata-sink.json` — Debezium JDBC sink в `ecl_postgres` (db `ecl`, schema `rdm_mirror`),
  upsert по record_key.
- Регистрация: `event-bus/connect/register.sh` (idempotent PUT config).

**Проблема, которую решили:** оба task'а были `FAILED` из-за **сетевой гонки** — `eventbus-connect`
поднимался раньше, чем получал доступ к docker-сетям соседних стеков (`UnknownHostException` для
source, «Unable to determine Dialect» для sink). Kafka Connect FAILED-task сам не перезапускает.
**Фикс:** дождаться сети и рестартнуть:
```bash
curl -X POST "http://localhost:8087/connectors/rdmmesh-refdata-source/restart?includeTasks=true"
curl -X POST "http://localhost:8087/connectors/ecl-refdata-sink/restart?includeTasks=true"
# статус:
curl -s http://localhost:8087/connectors/<name>/status | python3 -m json.tool
```
Контейнер `eventbus-connect` уже в сетях `rdmmesh_default` и `ecl_dbt_project_default` — хосты
`rdmmesh-postgres`/`ecl_postgres` резолвятся, креды и схема `rdm_mirror` валидны.

---

## 6. Операционные факты dev-стека (порты переопределены через `.env`!)

В docker ps порты **смещены** относительно дефолтов в `docker/README.md` (есть `.env` рядом с compose):

| Сервис | Хост-порт (фактический) | Дефолт в README |
|---|---|---|
| rdmmesh-postgres | **5436** | 5432 |
| rdmmesh-service API / admin | **8082 / 8083** | 8080 / 8081 |
| rdmmesh-keycloak | **8091** | 8090 |
| eventbus-connect (Kafka Connect REST) | 8087 | — |
| ecl_postgres / platform-postgres / om-postgres | 5433 / 5434 / 5435 | — |

«Connection refused» в DBeaver был именно из-за этого — Postgres на **5436**, а не 5432
(`ss -ltnp | grep <порт>` ничего не вернул на 5432 ⇒ никого нет ⇒ refused).

**Креды БД (dev):** суперюзер `rdmmesh_admin`/`rdmmesh_admin_dev`, runtime-роль `rdmmesh_app`/`rdmmesh_dev`,
db `rdmmesh`. Источники: `docker/docker-compose.yml`, `docker/postgres/init/00-create-app-role.sql`
(пароль `rdmmesh_app` **захардкожен**, env не читает!), `rdmmesh-app/src/main/resources/config*.yml`.
`make psql` / `psql -h localhost -p 5436 -U rdmmesh_admin -d rdmmesh`.

**OM сейчас:** `RDM_OM_BASE_URL=https://om-tls:8443/` прописан, но `RDM_OM_BOT_TOKEN` **пуст** → OM-резолв
не проходит, все текущие `om_user_id` — provisional (legacy). Для Phase 1/4 нужен рабочий bot-token.

---

## 7. Git / remotes

- Коммит Phase 1: **`4f58f2b`** на ветке `feat/om-rdmmesh-sync`.
- **GitHub** `origin` = `github.com/Aidarkose/rdmmesh.git` — запушено. PR можно открыть по ссылке из push-вывода.
- **GitLab** (локальный, `gitlab-ce 19.0.2` на `:8929`) — создан проект `root/rdmmesh`
  (`http://localhost:8929/root/rdmmesh.git`, default-branch `main`), запушены `feat/om-rdmmesh-sync` и `main`.
  Remote `gitlab` добавлен с чистым URL (без токена). PAT root для push:
  scopes `api`+`write_repository` (создан через `gitlab-rails runner`). ⚠️ если среда не приватная —
  отозвать и завести свой токен.
- Локального `mvn`/`java` нет — сборка только в Docker (`maven:3.9-eclipse-temurin-21`, кеш `~/.m2`).

---

## 8. Указатели / связанные материалы

- `SPEC.md` — §2.4 (identity mapping), §5.1 (Keycloak/AD), §3.1.
- [`E2-identity.md`](E2-identity.md) — исходный identity-эпик (provisional, JWT-флоу) — **частично
  устаревает** после Phase 1.
- Memory: `project_identity_role_redesign` (целевая модель + статус), `feedback_no_om_modifications`
  (vanilla-OM), `project_datamesh_ecosystem` (связь 5 проектов), `reference_openmetadata_source`
  (`~/projects/OpenMetadata/` — референс OM REST/JSON Schema для Phase 4).
- Ключевые файлы Phase 1: `rdmmesh-identity/src/main/java/bank/rdmmesh/identity/internal/`
  (`KeycloakIdentityPort.java`, `jwt/JwtValidator.java`, `dao/UserMappingDao.java`),
  `bootstrap/sql/migrations/identity/V051__reanchor_object_guid.sql`,
  `docker/keycloak/realms/realm-bank.json`.

---

## 10. SESSION 2 (2026-06-30) — Phases 2, 3, 4: реализация, деплой, e2e

> Это продолжение. Phase 1 (§1–9) уже была. Здесь — **что добавлено во второй сессии**.
> Всё закоммичено, запушено в оба git-а и **задеплоено** (`docker compose --project-directory
> <repo>/docker -f .../docker-compose.yml build/up -d --force-recreate --no-deps rdmmesh-service`,
> healthcheck `http://localhost:8083/healthcheck`).

### 10.0 TL;DR
- **Phase 2** — иерархия доменов (`parent_om_domain_id`) из OM + UI-дерево.
- **Phase 3** — доменный fallback владельца, owner subtree-гейт, и **смена маршрута на
  `STEWARD(author+submit) → OWNER`** (2-eyes, без второго стьюарда; ослаблён no-bypass-инвариант).
- **Phase 4(A+B)** — `domain_role_directory` и `om_user_id` теперь из **OM** (реальные OM-id) —
  **конец дрейфа identity**, ради которого всё затевалось.
- **Phase 4(C)** — нативная OM Event Subscription (Webhook) **готова и проверена** (§10.6):
  изменение домена/эксперта в UI OM авто-пропагируется в rdmmesh за ~4с. **Редизайн завершён.**

### 10.1 Phase 2 — иерархия доменов (`beb8feb` + хвост `3d1671c`)
- Миграция `bootstrap/sql/migrations/catalog/V018__domain_hierarchy.sql`: `catalog.domain.parent_om_domain_id uuid` (NULL=корень, хранится по OM-id, без FK) + index.
- `DomainDao`: COLUMNS/DomainRow += `parentOmDomainId`; `upsertByOmId` с `COALESCE(EXCLUDED.parent_om_domain_id, …)` (ownership-webhook шлёт null, авторитет — catalog-sync pull); рекурсивные CTE **`descendantOmIds(root)`** (поддерево) и **`ancestorOmIds(omId)`** (цепочка предков) — фундамент Phase 3.
- `OpenMetadataCatalogClient.listDomains` тянет `parent`; `CatalogMirrorPort.DomainMirror`/`CatalogSyncService` пробрасывают parent.
- `rdmmesh-spec/schema/entity/domain.json` += `parent_om_domain_id` (codegen Java POJO + TS; `src/generated` gitignored, регенерится из схемы в `node:22`-контейнере — локального node нет).
- UI `CatalogPage.tsx`: плоский список → antd `Tree` (`buildDomainForest` по `parent_om_domain_id→om_domain_id`).

### 10.2 Phase 3 — fallback владения, owner-гейт, маршрут STEWARD→OWNER
**Part 1 (`bd6c8ff`):** примитив резолва роли домена с подъёмом по иерархии.
- `DomainRoleDirectoryDao.resolveWithFallback(domainId, role)` (от домена вверх по предкам — ближайший держатель роли) и `isAuthorizedInSubtree(domainId, role, omUserId)` — рекурсивный SQL поверх `catalog.domain.parent_om_domain_id`. Проброшены через `ApproverDirectoryPort`.
- `WorkflowService`: OWNER-задача → per-asset owner, иначе доменный владелец с fallback (аддитивно).

**Part 2 (`a4bbf06`):**
- `WorkflowService.transition`: **owner subtree-гейт** — owner-approval (`decision.setApprover()`) разрешён только держателю BUSINESS_OWNER домена/предка (`isAuthorizedInSubtree`). Активен **только если у домена/предка определён владелец** в directory (иначе пермиссивен — bootstrap, не ломает IT). Не трогает per-asset asset-OWNER и RDM_ADMIN. Маппится в **403** (`InsufficientRoleException` → FORBIDDEN в `WorkflowTransitionResource`).
- `CatalogService.createCodeSet`: **default-владелец = владелец домена** (resolveWithFallback), НЕ создатель (создатель=стьюард; иначе он единственный owner-кандидат + self-approval-блок). Нет владельца → provisional не ставим. `ApproverDirectoryPort` прокинут через `CatalogModule`→`Application`.

**Workflow STEWARD→OWNER (`3a510ae`)** — потребовалось, иначе одинокий стьюард не мог submit'ить (см. §10.5):
- `WorkflowGraph.defaultStewardOwner()`: `DRAFT →submit(SUBMIT)→ IN_REVIEW →owner_approve(OWNER,setApprover)→ OWNER_APPROVED →publish→ PUBLISHED` (+owner_reject). **Без отдельной STEWARD-approve-ступени.**
- `WorkflowGraphInvariants` **ослаблен до 2-eyes**: путь в `OWNER_APPROVED` обязан содержать OWNER-ребро (kind=OWNER); отдельный STEWARD-этап больше НЕ обязателен. Независимость лиц (`owner ≠ created_by`) гарантирует OWNER-guard `StateMachine`. Если граф ВСЁ ЖЕ содержит STEWARD-ступень — OWNER обязан после неё (4-eyes не деградирует). Правило «в терминал только OWNER-ребро» сохранено. Тест `WorkflowGraphInvariantsTest` обновлён (2 устаревших «обязательный STEWARD» → `stewardOwnerTwoEyesIsCompliant` + `stewardKindEdgeIntoTerminalIsRejected`).
- `WorkflowService.resolveGraph`: дефолт домена без шаблона (и fail-safe) → `defaultStewardOwner`.
- `WorkflowService.validateAssignee(.., stewardStep)`: STEWARD-проверки assignee (в справочнике, ≠author) — только если граф содержит STEWARD-этап (`nextRequiredRole(IN_REVIEW)==STEWARD`); owner-проверки (в справочнике, ≠author) — всегда.
- **Решение пользователя:** ослабление 4-eyes→2-eyes согласовано явно (маршрут «строго STEWARD→OWNER» из целевой модели).

### 10.3 Phase 4(A+B) — directory и om_user_id из OM (`06b5aac`) — КОНЕЦ ДРЕЙФА
- **(A)** `OpenMetadataCatalogClient.listDomains` тянет `fields=…,owners,experts`; `OmEntity` несёт `owners`/`experts` (`OmUserRef id/name/displayName/type`). `CatalogSyncService.resync`: `owners→BUSINESS_OWNER`, `experts→STEWARD` (только `type=user`) → полная замена `domain_role_directory` через `ApproverDirectoryPort.reload(entries, "OM_GENERATED")` (reload пропускается, если список доменов OM пуст — не вайпаем). Добавлен overload `reload(entries, source)`.
- **(B)** Биндинг `om_user_id` (existing `KeycloakIdentityPort` + `OpenMetadataUserClient` `GET /api/v1/users/name/{username}` — по OM-**username**, не email) резолвит **реальный OM-id**. Легаси-провизорные `om_user_id` в dev-mapping обнулены разово (`UPDATE identity.rdm_user_mapping SET om_user_id=NULL WHERE username IN (…)`) → ребиндятся при логине. **Итог: `mapping.om_user_id == directory.om_user_id` для всех — дрейф устранён by-design** (оба из OM).

### 10.4 Подключение rdmmesh ↔ OpenMetadata (операционка Phase 4)
- OM запущен в стеке: `om-server` (v1.12.5, healthy), `om-tls` (TLS-терминатор :8443), `om-ingestion`, `om-postgres`, `om-opensearch`. Auth: **basic**, админ `admin@open-metadata.org` пароль `admin` (base64 `YWRtaW4=`).
- `rdmmesh` ходит в OM по `RDM_OM_BASE_URL=https://om-tls:8443/` (truststore `/opt/rdmmesh/tls/om-ca-truststore.p12` уже в JVM-опциях контейнера). Из контейнера `rdmmesh-service` есть `curl` и доступ к `om-server:8585` (http) — удобно для ручных вызовов OM API.
- **Bot-token:** ingestion-bot OM не отдаёт в открытом виде → создан **выделенный `rdmmesh-bot`**: `PUT /api/v1/users {"name":"rdmmesh-bot","email":"rdmmesh-bot@open-metadata.org","isBot":true,"authenticationMechanism":{"authType":"JWT","config":{"JWTTokenExpiry":"Unlimited"}}}` (JWT в ответе) → `PUT /api/v1/bots {"name":"rdmmesh-bot","botUser":"rdmmesh-bot"}`. Токен прописан в `docker/.env` `RDM_OM_BOT_TOKEN` (gitignored; не в репо).
- **Триггер pull:** `POST /api/v1/webhooks/om/catalog-sync` (без подписи всё равно тянет; HMAC-ключ `RDM_OM_WEBHOOK_HMAC_KEY`). Результат: domains_synced=4 (+иерархия `credit_scorr⊂ecl`), roles_synced=17, directoryEntries=8.
- **Логин в OM для админ-операций:** `POST /api/v1/users/login {"email":"admin@open-metadata.org","password":"YWRtaW4="}` → `accessToken`; затем `Authorization: Bearer`.

### 10.5 e2e-рецепт owner-гейта (повторяемый; движок=enum, rootPath `/api/v1`, app `:8082`, admin `:8083`)
1. Токены через Keycloak password-grant: `POST http://localhost:8091/realms/bank/protocol/openid-connect/token` (`client_id=rdmmesh-ui`, `username=<u>`, `password=dev`). Юзеры: marat.suleimenov=RDM_STEWARD, aigerim.bekova/dana.akhmetova=RDM_OWNER, timur.iskakov=RDM_STEWARD, dev-admin=RDM_ADMIN.
2. Создать справочник: `POST /api/v1/codesets/by-domain/{domainId}` `{"name":"…"}` (RDM_STEWARD/ADMIN). Версия: `POST /api/v1/versions/by-codeset/{codesetId}` `{}` → DRAFT 0.1.0.
3. Submit: `POST /api/v1/versions/{vId}/transitions` `{"to":"IN_REVIEW","assignee":{"domain_id","steward_om_user_id","owner_om_user_id"}}`. **assignee обязателен** (BR-21); в 2-eyes steward-assignee инертен, но поле непустое; owner_om_user_id обязан быть BUSINESS_OWNER домена в directory и ≠ автор.
4. Owner-approve: `POST …/transitions` `{"to":"OWNER_APPROVED"}` токеном владельца. Свой домен → 200 (далее авто-publish → PUBLISHED). Чужой домен → **403** (гейт).
- **NB про очистку:** создание справочника провижнит relational physical-таблицы `rd_data.{domain}__{name}__{current,draft,history}` + строку `authoring.codeset_physical_table` (unique по schema/table). При повторных e2e их НАДО чистить (`DROP TABLE rd_data.…` + `DELETE FROM authoring.codeset_physical_table`), иначе следующий `createDraft` падает **500 duplicate key**.
- **Таблицы для cleanup:** `workflow.{approval_task,version_route,workflow_transition}`, `authoring.{code_set_version_reviewer,code_set_version,codeset_physical_table}`, `rd_data.*` (DROP), `ownership.rdm_asset_ownership`, `catalog.{code_set_schema,code_set}`.

### 10.6 Phase 4(C) — нативная OM Event Subscription: ГОТОВА и ПРОВЕРЕНА (2026-06-30)
- **Нативный механизм = OM Event Subscription** (настраивается в **UI OM**: Settings → Alerts/Notifications), НЕ rdmmesh-эндпоинт. `POST /api/v1/webhooks/om/catalog-sync` — это **приёмник на стороне rdmmesh** (`CatalogSyncWebhookResource`, HMAC опционален; ручной `curl` на него — лишь симуляция нативного триггера).
- В OM **уже создана** подписка `Domain_and_Roles_sync_for_RDMmesh` (alertType=Notification, `enabled:true`): destination **Webhook** → `http://rdmmesh-service:8080/api/v1/webhooks/om/catalog-sync` (по mesh-net; om-server резолвит `rdmmesh-service`). `filteringRules.resources=["all"]`, `pollInterval=10s`, `retries=3`.
- **Проверено вживую (пользовательский сценарий через UI OM):** `PATCH /api/v1/domains/{ECL}` (= действие «добавить эксперта домена» в UI) → нативный Alert сам POST'нул webhook за ~4с → resync → `domain_role_directory` обновился (ecl STEWARD += dana), затем реверт так же авто-пропагировался. Кода менять не пришлось.
- **Опциональный рефайн:** `resources=["all"]` → триггерит на ЛЮБОЕ изменение сущности (full resync каждый раз). Можно сузить фильтр в UI OM до `domain`/`team`/`role`-событий (эффективность; на корректность не влияет, resync идемпотентен).
- **Управление подпиской через OM API** (admin-токен): `GET/POST /api/v1/events/subscriptions`, `GET /api/v1/events/subscriptions/name/{name}`. JSON Patch домена: `PATCH /api/v1/domains/{omId}` (Content-Type `application/json-patch+json`); NB порядок массива `experts`/`owners` в OM не гарантирован — для удаления конкретного юзера используйте `replace /experts` целиком, не `remove /experts/{i}`.
- Per-asset ownership webhook (E7, `OwnershipWebhookService`) — отдельный, уже существующий механизм (per-CodeSet asset-роли).

### 10.7 Реальные OM-id (dev, для справки)
`marat=1d20721d-b8d4-4b37-9347-ff08c59ac803`, `aigerim=9dabec2b-9d34-40a9-a402-8ee8af0e67cd`,
`dana=0f133f3b-de5d-4015-b66e-c95570c76296`, `timur=df698ea3-8a30-49ba-a170-5fdf4296f4cc`.
OM-домены: `ECL`(owner=aigerim, expert=marat), `Airfly`(owner=dana, expert=timur),
`Credit_Scorr`(⊂ECL; owner=timur, expert=aigerim).
