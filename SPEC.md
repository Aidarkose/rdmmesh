# rdmmesh — спецификация для разработки

> **Назначение документа.** Самодостаточная постановка задачи и архитектура для AI-агентов и инженеров, которые будут проектировать, реализовывать и ревьюить систему `rdmmesh`. Документ предполагает, что читатель не участвовал в обсуждениях и не видел переписки — вся необходимая контекстная информация изложена ниже.
>
> **Версия документа.** 0.1 (черновик после архитектурной фазы, до начала реализации).
>
> **Связанные системы.** OpenMetadata 1.12.x (внедряется параллельно), корпоративный Active Directory, Keycloak (как broker между AD и OIDC-клиентами).

---

## 1. Постановка задачи

### 1.1. Контекст

Банк внедряет подход **Data Mesh**. В качестве **корпоративного каталога метаданных** выбран **OpenMetadata** последней версии (1.12.x, on-prem). OpenMetadata закрывает: discovery, lineage, governance, data quality, glossary/classification, ownership/teams, data domains/data products.

Однако одного каталога недостаточно. Бизнес-домены банка должны иметь возможность **самостоятельно создавать и сопровождать жизненный цикл бизнес-справочников** (Reference Data) — структурированных, версионируемых, согласуемых перечней, которые потребляют downstream-системы (ETL, BI, риск-движки, операционные приложения).

Существующие open-source RDM-решения (Pim Core, AtroCore и аналоги) **отвергнуты по двум причинам**:
1. Чрезмерная сложность: PIM-фокус (Product Information Management) перегружает архитектуру категориями, медиа, e-commerce-связками, не нужными банку.
2. Архитектурный и UX-диссонанс с OpenMetadata: разные стэки, разные UI-парадигмы, разная модель governance.

Решение — **построить собственную систему `rdmmesh`** (Reference Data Management for Mesh), архитектурно и визуально согласованную с OpenMetadata, но с чёткой границей ответственности (только RDM, без MDM).

### 1.2. Scope

**В scope:**

- Создание и редактирование справочников (CodeSet) бизнес-доменами.
- Иерархические справочники (внутри справочника и cross-codeset references).
- Композитные ключи (для матриц и многомерных данных).
- Версионирование справочников (semver, immutable snapshot per published version).
- Bitemporal-хранение (system time + effective time) для регуляторного reproducibility.
- Workflow согласования (4-eyes: Author → Steward → Owner → Published).
- Локализация labels на двух языках: русский и английский.
- Аутентификация через корпоративный AD (через Keycloak-broker), общий контур безопасности с OpenMetadata.
- Получение информации о бизнес-доменах (название, код, описание, ownership домена) из OpenMetadata. **OM — мастер-система** по определению доменов: rdmmesh ведёт только локальный зеркальный кэш (`catalog.domain`), синхронизируемый из OM, и не позволяет создавать/редактировать домены в собственном UI.
- Получение информации о владельцах и экспертах справочников из OpenMetadata (OM — единственный источник истины для ownership).
- Публикация справочников для downstream-потребителей (REST API, bulk export, webhooks; Kafka в будущей версии).
- Регистрация справочников в OpenMetadata через **ingestion-коннектор** (OM сам забирает метаданные, RDM не пушит).
- Append-only audit-журнал и цифровая подпись approver'ов.

**Вне scope (не делаем — намеренная граница):**

- **Master Data Management** (MDM): matching, deduplication, golden record, survivorship rules, fuzzy linking. Если в будущем потребуется — это отдельный продукт.
- Provisioning и enforcement доступов (для домена Security/Access Matrix). Матрицу `Position×System→Permission` rdmmesh **только ведёт**; раздачу прав в системы выполняет корпоративный IdM/IGA, читающий rdmmesh по REST/Kafka.
- Хранение и обработка операционных бизнес-данных (это не data warehouse).
- Замена OpenMetadata Glossary или Classification — это разные сущности с разной семантикой.

### 1.3. Пилотные домены

Первая боевая поставка обслуживает два бизнес-домена:

1. **Подразделение рисков (Risk) — справочники IFRS9** для расчёта резервов на возможные потери:
   - Матрицы PD/LGD/EAD по сегмент×рейтинг×горизонт.
   - Стадии SICR (Significant Increase in Credit Risk: Stage 1/2/3).
   - Макроэкономические сценарии (baseline/adverse/optimistic), обновляются при поступлении от регулятора.
   - Кривые дисконтирования, рейтинговые шкалы, продуктовые сегменты.
   - **Регуляторное требование:** воспроизводимость справочника на любую отчётную дату задним числом. Любая старая published-версия должна быть полностью восстанавливаема.

2. **Подразделение безопасности (Security) — матрицы доступов** должностей к системам банка:
   - Иерархические справочники: Department → Division → Position.
   - Справочник Systems (информационные системы банка).
   - Справочник Permission Levels (Read/Write/Admin/Custom).
   - Матрица: composite key `(position_code, system_code)` → атрибут `permission_level`.
   - **Особенность:** bulk-операции в одной заявке (новая система → дефолтные права для всех позиций).

### 1.4. Объёмы

- Десятки–сотни справочников на старте, потенциально до тысяч в горизонте 3–5 лет.
- Размер одного справочника: от десятков записей (стадии SICR) до миллионов (например, полный иерархический справочник продуктов).
- **Стандартный PostgreSQL 16 OLTP** покрывает потребности; партиционирование, sharding, Iceberg cold-tier, ClickHouse — **не нужны** в обозримом горизонте.

---

## 2. Бизнес-процессы и требования

### 2.1. Роли пользователей

| Роль | Скоуп | Что делает |
|---|---|---|
| **Consumer** | Глобальный | Читает published-версии справочников через UI или REST API |
| **Author** | Per Domain | Создаёт CodeSet, редактирует draft-версию, добавляет/изменяет CodeItem, подаёт draft на ревью |
| **Steward** | Per Domain | Делает технический ревью draft (полнота, корректность атрибутов, валидация по схеме), approve или reject с комментарием |
| **Owner** | Per Domain (для каждого CodeSet) | Делает бизнес-approve, инициирует publish. Ownership назначается в OpenMetadata (см. §2.4) |
| **Expert** | Per Domain (для каждого CodeSet) | Консультирует, может комментировать, не имеет прав approve. Назначается в OpenMetadata |
| **Schema Designer** | Per Domain | Проектирует CodeSetSchema (структуру атрибутов CodeItem) для нового справочника |
| **RDM Admin** | Глобальный | Управляет доменами, шаблонами workflow, настройками интеграции. **Не имеет права обходить workflow** |

**Принцип маппинга ролей:**
- **Базовые функциональные роли** (Author, Consumer, Schema Designer, Admin) — назначаются через AD-группы, привозятся в RDM в `groups` claim Keycloak JWT.
- **Asset-level роли** (Owner, Expert, Steward конкретного CodeSet) — назначаются в OpenMetadata, приходят в RDM через webhook от OM Event Subscription и хранятся в таблице `rdm_asset_ownership`.

### 2.2. Сквозной бизнес-процесс: жизненный цикл справочника

#### Этап 1. Создание справочника

1. **Schema Designer** домена создаёт новый CodeSet:
   - Указывает имя (`country_iso`, `ifrs9_stages`, `position_system_matrix`).
   - Привязывает к Domain (один из существующих доменов из OM, выбирается в форме).
   - Проектирует CodeSetSchema — JSON Schema атрибутов CodeItem (например, для `country_iso`: `{iso2, iso3, name_ru, name_en, currency_code, region}`).
   - Указывает: одиночный ключ или composite key, поддерживается ли иерархия.
2. CodeSet создаётся в статусе **без published-версии**. На этом этапе owner ещё не назначен (см. §2.4 про bootstrap-период).
3. CodeSet появляется в OpenMetadata после ближайшего цикла ingestion (раз в час, конфигурируемо).

#### Этап 2. Наполнение и редактирование

1. **Author** открывает CodeSet, создаёт первую draft-версию (`v0.1.0-draft`).
2. Author редактирует CodeItem'ы:
   - Через grid-редактор (TanStack Table): inline edit, virtual scroll, copy-paste из Excel.
   - Через bulk-импорт (CSV/XLSX): валидация по CodeSetSchema, отчёт об ошибках.
   - Через tree-редактор (для иерархических): drag-drop узлов, изменение parent_code.
3. Каждое сохранение — внутри одной draft-версии, history черновика хранится для отката.
4. Author может в любой момент:
   - Сравнить draft с последней published-версией (diff: added/changed/removed/moved).
   - Сохранить и закрыть, продолжить позже.
   - Подать на ревью (transition: `DRAFT → IN_REVIEW`).

#### Этап 3. Согласование (4-eyes)

Стандартный workflow MVP жёстко зашит, кастомизация — V2:

```
[DRAFT] ──submit──▶ [IN_REVIEW] ──steward_approve──▶ [STEWARD_APPROVED]
                          │                                  │
                          │ steward_reject                   │ owner_approve
                          ▼                                  ▼
                       [DRAFT]                       [OWNER_APPROVED]
                                                            │
                                                            │ publish (автоматически
                                                            │  после owner_approve
                                                            │  или ручной trigger)
                                                            ▼
                                                      [PUBLISHED]
                                                            │
                                                            │ при появлении
                                                            │  следующей PUBLISHED
                                                            ▼
                                                     [DEPRECATED]
```

Правила переходов:
- Только Steward домена может выполнить `steward_approve`/`steward_reject`.
- Только Owner CodeSet (из OM ownership) может выполнить `owner_approve`.
- Author не может быть одновременно Steward и Owner — проверка self-approval.
- При reject указывается обязательный комментарий, draft возвращается Author'у.
- При publish: предыдущая published-версия автоматически переводится в DEPRECATED с заполнением `effective_to`.

**Особый случай — Emergency hotfix** (V1+, не MVP): отдельный шаблон, разрешает Owner'у публиковать без steward_approve, но создаёт обязательную задачу пост-факт ревью.

#### Этап 4. Публикация и потребление

1. После publish создаётся **immutable snapshot**: все CodeItem копируются в новую версию с заморозкой `system_to` для предыдущей.
2. Snapshot подписывается: `signature = HMAC_SHA256(version_content_hash || approver_user_id || timestamp, server_secret)`. Подпись хранится в `code_set_version.approval_signature`.
3. Webhook рассылается зарегистрированным потребителям (HMAC-подписанный payload, идемпотентный, with retry).
4. Consumer-системы получают данные:
   - **REST**: `GET /api/v1/rdm/{domain}/{codeset}/items?version=published&as_of=2026-05-03&knowledge_as_of=2026-05-15&lang=ru`
   - **Bulk export**: `GET .../export?format=csv|json|parquet`
   - **dbt source** (V2): автогенерация `sources.yml`.
   - **Kafka** (V2): топик `rdm.<domain>.<codeset>` с CDC.

#### Этап 5. Депрекация и архив

- Published-версия становится DEPRECATED при появлении следующей published.
- DEPRECATED-версии **не удаляются никогда** (регуляторное требование IFRS9).
- API позволяет читать DEPRECATED версии явным указанием `version=<semver>`.

### 2.3. Bitemporal-модель

Каждая запись и каждая версия хранят **две оси времени**:

| Ось | Колонки | Семантика |
|---|---|---|
| **Effective time** (бизнес-время) | `effective_from`, `effective_to` | Когда запись действует/действовала с точки зрения бизнеса. Например, ставка ЦБ действует с 15.03.2026 по 14.04.2026. |
| **System time** (системное время) | `system_from`, `system_to` | Когда мы знали об этой записи в системе. Например, мы узнали о ставке 14.03.2026 в 18:00 (опубликовали справочник). |

Это даёт возможность отвечать на запросы вида:
- *«Какой был справочник стадий SICR на 31.12.2025?»* → `effective_at = 2025-12-31`, `knowledge_at = NOW()`.
- *«Что мы знали об этом справочнике 15.01.2026 как о действовавшем на 31.12.2025?»* → `effective_at = 2025-12-31`, `knowledge_at = 2026-01-15`.

**Обязательно для MVP** из-за регуляторных требований домена Risk/IFRS9.

### 2.4. Связь с OpenMetadata: бизнес-домены и ownership (ключевое решение)

**Принцип: слабая связанность. RDM ничего не пушит в OM активно. OM — единственный источник истины (мастер-система) по двум классам сущностей:**

1. **Бизнес-домены** — название, код (FQN-сегмент), описание, иерархия sub-domain'ов, owner самого домена. RDM ведёт локальный mirror (`catalog.domain`), но не создаёт и не редактирует домены в своём UI.
2. **Ownership на data assets** — owner, steward, expert, approver конкретного CodeSet'а. RDM хранит это в `ownership.rdm_asset_ownership` тоже как mirror.

#### Поток "RDM → OM" (только pull, через ingestion)

- В OpenMetadata деплоится **отдельный ingestion-коннектор** `om-rdmmesh-source` (Python-пакет, артефакт OM, не часть rdmmesh-service).
- Коннектор по cron (раз в час) ходит в REST API rdmmesh, забирает метаданные о CodeSets и регистрирует их в OM как `Table` в синтетическом database service `rdmmesh`.
- FQN таблиц: `rdmmesh.<domain_name>.<codeset_name>`.
- Маппинг колонок: атрибуты `CodeSetSchema` → `Table.columns`.
- Description, теги CodeSet → description, tags таблицы.
- **Owners в Table — НЕ передаются через ingestion**. Owner назначается потом, в UI OM.

#### Поток "OM → RDM" (push, через webhook)

- В OM регистрируется одна **Event Subscription** с фильтром:
  - `eventType ∈ {ENTITY_CREATED, ENTITY_UPDATED, ENTITY_SOFT_DELETED}`
  - `entityType ∈ {domain, table}`
  - Для `table`: интересны изменения полей `owners`, `experts`, `reviewers`; FQN-фильтр `rdmmesh.*`.
  - Для `domain`: интересны и атрибуты (`name`, `displayName`, `description`, `parent`), и `owners`/`experts`. FQN-фильтр не нужен — RDM зеркалит **все** домены OM, потому что любой из них может оказаться целевым для нового справочника.
  - URL: `https://rdm.bank/webhooks/om/ownership` (имя сохранено для совместимости; обрабатывает оба класса событий).
  - Auth: bot-токен в `Authorization`, payload подписан HMAC.
- Endpoint в RDM (`POST /webhooks/om/ownership`):
  - **Если `entityType=domain`** — `UPSERT INTO catalog.domain (om_domain_id, name, display_name, description, label_ru, label_en, tags)`. Soft-delete переводит `deleted_at`, не удаляет физически (downstream-CodeSet'ы могут на него ссылаться).
  - **Если `entityType=table` (FQN `rdmmesh.*`)** — парсит ChangeEvent, извлекает delta по полям `owners`/`experts`/`reviewers`, делает `UPSERT INTO rdm_asset_ownership (asset_id, asset_type, om_user_id, role, assigned_at, is_provisional=false)`. Маппинг: `owners` → `OWNER`, `experts` → `EXPERT`, `reviewers` → `APPROVER` (для steward подобной семантики в OM нет — steward = expert или отдельная политика).
  - Любой обработанный event идемпотентен по `source_event_id` и инвалидирует permission cache.

#### Bootstrap-период (CodeSet создан, ingestion ещё не прошёл)

1. Author создаёт CodeSet `IFRS9 Stages` в RDM, выбирая Domain из локального mirror'а `catalog.domain` (заполненного предыдущим domain-webhook'ом OM). Поле owner в форме отсутствует — единственный источник OM.
2. RDM создаёт **provisional owner**: `INSERT INTO rdm_asset_ownership (..., om_user_id=<creator>, role=OWNER, is_provisional=true)`.
3. Через час ingestion забирает CodeSet, создаёт Table в OM без owner.
4. Кто-то в OM назначает реального owner.
5. RDM получает webhook → `UPSERT` овnership с `is_provisional=false`.
6. В UI RDM, пока owner provisional — баннер «Owner не утверждён в OpenMetadata, действует временное назначение».
7. Publish **не блокируется** на provisional-период (нельзя останавливать банковские процессы на задержке ingestion), но в audit фиксируется `owner_was_provisional=true`.

#### Bootstrap-период для домена (домен ещё не пришёл из OM)

Аналогично, но реже: на самом старте внедрения, когда webhook OM ещё не настроен либо ни одного relevant domain-event ещё не пришло, mirror `catalog.domain` пуст, и Author не может выбрать domain при создании CodeSet'а.

- В этот период `RDM_ADMIN` имеет доступ к **bootstrap REST**: `POST /api/v1/domains` принимает `om_domain_id`, `name`, `display_name`, по которым в `catalog.domain` создаётся mirror-row. Это техническая мера: **в нормальной операции** `RDM_ADMIN` доменом не управляет, всё течёт из OM.
- Идемпотентность по `om_domain_id` гарантирует, что последующий webhook от OM с тем же `om_domain_id` корректно "поглотит" bootstrap-row через UPSERT, не создав дубликата.
- Как только webhook'и стабильно работают, bootstrap-endpoint можно отключить feature-флагом или ограничить ролью только в нон-prod-средах.

#### Маппинг идентификаторов

- В OM пользователь имеет `User.id` (UUID) и `User.name` (sAMAccountName).
- В Keycloak пользователь имеет `sub` (UUID, **другой**) и `preferred_username` (sAMAccountName).
- Нужна таблица `rdm_user_mapping (om_user_id, keycloak_sub, username)`, заполняется лениво при первом логине пользователя (lookup в OM API по `name`).

### 2.5. Бизнес-требования (структурированно)

| ID | Требование | Источник | Критичность |
|---|---|---|---|
| BR-01 | Бизнес-домены создают и ведут справочники самостоятельно, без участия IT | Постановка | MUST |
| BR-02 | Поддержка иерархических справочников (внутри одного и через cross-references) | Security/Access Matrix | MUST |
| BR-03 | Поддержка композитных ключей (N-мерные) | Risk/IFRS9 + Security | MUST |
| BR-04 | Версионирование с semver и immutable snapshot после publish | Постановка | MUST |
| BR-05 | Bitemporal: воспроизводимость справочника на любую дату задним числом | Risk/IFRS9 (регулятор) | MUST |
| BR-06 | Workflow 4-eyes: Author → Steward → Owner → Published, без обхода | Compliance | MUST |
| BR-07 | Цифровая подпись approver'а на published-версии | Compliance | MUST |
| BR-08 | Append-only audit-журнал всех действий | Compliance | MUST |
| BR-09 | Локализация labels CodeItem (ru + en) | Постановка | MUST |
| BR-10 | Аутентификация через корпоративный AD, общий контур с OM | Постановка | MUST |
| BR-11 | Owner/Expert/Approver справочников приходят из OpenMetadata, не дублируются в RDM | Постановка | MUST |
| BR-11a | Бизнес-домены (название, код, описание, ownership домена) — мастер-данные OpenMetadata; rdmmesh ведёт только локальный зеркальный кэш, синхронизируемый через webhook от OM | Постановка | MUST |
| BR-12 | OM узнаёт о справочниках через ingestion (pull-модель) | Постановка | MUST |
| BR-13 | REST API для consumer'ов (read-only) с поддержкой `as_of` параметров | Распределение | MUST |
| BR-14 | Bulk export (CSV/XLSX/JSON/Parquet) | Распределение | SHOULD |
| BR-15 | Webhooks для consumer-систем при publish/deprecate | Распределение | SHOULD |
| BR-16 | Bulk-операции в одном draft (новая система → дефолтные права для всех позиций) | Security/Access Matrix | SHOULD |
| BR-17 | Emergency hotfix workflow (Owner может публиковать без Steward, с пост-факт ревью) | Risk/IFRS9 (по ситуации) | COULD (V1+) |
| BR-18 | Custom BPMN workflow templates per Domain | — | COULD (V2+) |
| BR-19 | Kafka outbound для streaming consumers | — | COULD (V2+) |
| BR-20 | UI визуально согласован с OpenMetadata | Постановка | SHOULD |

---

## 3. Техническое задание

### 3.1. Технологический стек (Lean MVP)

| Слой | Решение | Обоснование |
|---|---|---|
| Backend язык/runtime | **Java 21** | Согласование с OpenMetadata (упрощает рекрутинг, переиспользование паттернов) |
| Web framework | **Dropwizard 4** | Тот же, что у OM; можем форкать паттерн `EntityRepository` |
| Persistence | **JDBI3 + Flyway** | Тот же подход, что у OM |
| База данных | **PostgreSQL 16** | OM-совместимо, JSONB для extensions, FTS из коробки |
| Поиск | **Postgres FTS** (`tsvector` со словарями `russian` + `english`) **+ pg_trgm** | На текущих объёмах хватит; за интерфейсом `SearchPort` для будущей замены на ES |
| Workflow | **Enum state machine** (~200 строк собственного кода за `WorkflowPort`) | Один шаблон 4-eyes; Flowable избыточен для MVP |
| Identity | **Keycloak** (broker к локальному AD по LDAP+Kerberos), **OIDC + PKCE** | Общий с OM realm, AD-группы → role claims |
| Outbound | **Webhooks** (HMAC-подписанные, идемпотентные, retry с backoff) | Kafka — V2+ за тем же `OutboundPort` |
| Schema | **JSON Schema** → datamodel-code-generator (Java POJO + TS типы + Python Pydantic для ingestion) | Schema-first подход, как у OM |
| Frontend | **React 18 + TypeScript 5 + AntD 4.24 + TanStack Table + react-i18next + Vite** | Lean: без Tailwind, без AG Grid, без react-flow в MVP |
| Dev environment | **Docker Compose** (Postgres + Keycloak + сервис) | — |
| Production deploy | **Helm chart** (Kubernetes) | Стандарт |

**Внешние зависимости в production-деплое: 3 компонента** — Postgres, Keycloak, JVM-сервис. ES, Kafka, Redis, Iceberg, Airflow — **не требуются** (Airflow есть на стороне OM для ingestion-коннектора).

### 3.2. Принципы и инварианты

1. **Schema-first**. JSON Schema — единственный источник истины для контрактов сущностей. Кодогенерация поверх. Никаких ручных POJO/типов параллельно.
2. **Modular monolith с явными bounded contexts**. Один JVM, один деплой, но восемь Maven-модулей с строгими интерфейсами между ними.
3. **Ports & Adapters** для всех заменяемых компонентов: `SearchPort`, `WorkflowPort`, `OutboundPort`, `IdentityPort`, `OwnershipPort`. Замена реализации не требует изменений в бизнес-логике.
4. **Слабая связанность с OpenMetadata.** RDM не вызывает OM API в синхронных бизнес-процессах. Ingestion — pull со стороны OM. Ownership — асинхронный webhook.
5. **Immutability published-версий.** После publish — никаких UPDATE/DELETE на CodeItem'ах этой версии. Любые изменения = новая версия.
6. **Append-only audit.** Таблица `audit_log` без UPDATE/DELETE на уровне БД-роли (`GRANT INSERT ONLY` для app-роли).
7. **No-bypass workflow.** Даже у `RDM_ADMIN` нет permission `workflow.skip`. Hotfix только через явный шаблон.
8. **Bitemporal с первого дня.** Колонки `effective_*` и `system_*` присутствуют в схеме сразу, даже если UI их пока не показывает.
9. **Composite keys с первого дня.** Структура CodeItem поддерживает N-мерный ключ, даже если первые справочники — одиночные.

### 3.3. Восемь bounded contexts

Каждый — отдельный Maven-модуль. Между модулями — только интерфейсы из общего модуля `rdmmesh-api`. Compile-time запрет cross-cuts через ArchUnit-тесты.

| Модуль | Ответственность | Owner-данные | Чужие зависимости (через интерфейсы) |
|---|---|---|---|
| `rdmmesh-api` | Общие интерфейсы, DTO, Port-определения | — | — |
| `catalog` | CodeSet metadata, CodeSetSchema, домены, tags. Только metadata, не items | Postgres schema `catalog` | — |
| `authoring` | Drafts, CodeItem CRUD, импорт/валидация по CodeSetSchema | Postgres schema `authoring` | `catalog` (read), `WorkflowPort`, `OwnershipPort` |
| `workflow` | State machine, approval-задачи, переходы статусов | Postgres schema `workflow` | `OwnershipPort`, события из `authoring`/`publishing` |
| `publishing` | Snapshot creation, freeze, подпись, эмиссия события publish | Postgres schema `publishing` | `authoring` (read), `WorkflowPort`, `OutboundPort` |
| `distribution` | REST/bulk-export для consumer'ов, read-only | Read-replica или read-views | `catalog` (read), `publishing` (read) |
| `identity` | Keycloak JWT валидация, маппинг AD-групп → базовых ролей | Postgres schema `identity` | — |
| `ownership` | Webhook receiver от OM, таблицы `rdm_asset_ownership` и `rdm_user_mapping` | Postgres schema `ownership` | — |
| `audit` | Append-only журнал, подпись, экспорт | Postgres schema `audit` (INSERT ONLY) | Подписан на event-bus, никого не читает |

Правила, которые проверяются ArchUnit'ом:
- Никакой модуль не импортирует пакеты другого модуля напрямую — только через `rdmmesh-api`.
- `audit` не импортирует ничего, кроме `rdmmesh-api`.
- `distribution` не делает write в БД.
- `catalog` и `authoring` — единственные write-модули в Postgres schemas `catalog`/`authoring`.

### 3.4. Доменная модель (ядро)

```
Domain (FK→om_domain_id; mirror из OpenMetadata, синхронизируется push-webhook'ом OM, см. §2.4)
  └── CodeSet                        «country_iso», «ifrs9_stages», «position_system_matrix»
        ├── CodeSetSchema            JSON Schema атрибутов CodeItem
        ├── KeySpec                  одиночный код или composite (key_part_1..key_part_n)
        ├── HierarchyMode            NONE | INTRA_CODESET | CROSS_CODESET
        ├── ReleaseChannels          по умолчанию prod; sandbox опционально
        └── CodeSetVersion[]         иммутабельный snapshot после публикации
              ├── version            SemVer (1.0.0 / 1.1.0 / 2.0.0)
              ├── status             DRAFT | IN_REVIEW | STEWARD_APPROVED |
              │                      OWNER_APPROVED | PUBLISHED | DEPRECATED | REJECTED
              ├── effective_from, effective_to    бизнес-время
              ├── system_from,    system_to       системное время (bitemporal)
              ├── created_by, reviewed_by[], approved_by, published_by  om_user_id
              ├── content_hash      SHA-256 от детерминированной сериализации
              ├── approval_signature HMAC(content_hash || approved_by || timestamp, secret)
              ├── owner_was_provisional  флаг для audit
              └── CodeItem[]
                    ├── key_parts    JSONB: ["KZ"] или ["RETAIL", "BB", "12M"]
                    ├── label        Map<lang, string>: {"ru": "Казахстан", "en": "Kazakhstan"}
                    ├── parent_key   для intra-codeset hierarchy
                    ├── parent_ref   {codeset_id, key_parts} для cross-codeset
                    ├── attributes   JSONB, валидируется по CodeSetSchema
                    ├── order_index
                    ├── status       ACTIVE | RETIRED
                    └── effective_from, effective_to
```

#### Иерархии

- **Adjacency list** (`parent_key`/`parent_ref`) для редактирования.
- **Closure table** `code_item_closure(version_id, ancestor_key, descendant_key, depth)` для быстрых subtree-запросов и обхода предков.
- Поддержка intra-codeset (Position → Department → Division) и cross-codeset (City → ссылка на Country).

#### Версионирование (snapshot strategy)

- Каждая published-версия — **полный snapshot** всех CodeItem в этой таблице, помеченный `version_id`.
- Партиционирование `code_item` по `version_id` **отключено в MVP** (объёмы небольшие), но pluggable — добавляется DDL-миграцией без рефакторинга кода.
- При создании новой draft-версии: `INSERT INTO code_item (version_id=<new_draft>, ...) SELECT ... FROM code_item WHERE version_id=<last_published>`.
- Разница между версиями вычисляется через view `code_item_diff(from_version, to_version) → (op, key, changed_fields)`.

#### Обязательные индексы (MVP)

- `code_item (version_id, key_parts)` — primary lookup.
- GIN-индекс на `code_item.attributes` (JSONB) для запросов по атрибутам.
- GiST-индекс на `code_item (effective_from, effective_to)` — bitemporal.
- B-tree на `code_item (parent_key)` для иерархии.
- `tsvector`-индекс на `code_set.description` и `code_item.label->>'ru'`, `label->>'en'` для FTS.
- `pg_trgm` GIN на тех же полях для typo-tolerant.

### 3.5. REST API (контракт)

**Базовый префикс:** `/api/v1`. Все endpoint'ы требуют JWT в `Authorization`.

#### Catalog (read для всех authenticated, write для Schema Designer/Author)

```
GET    /domains                                    список доменов
GET    /domains/{domain}/codesets                  список CodeSet в домене
POST   /domains/{domain}/codesets                  создать CodeSet
GET    /codesets/{codeset_id}                      метаданные CodeSet
PATCH  /codesets/{codeset_id}                      обновить metadata (description, tags)
GET    /codesets/{codeset_id}/schema               текущая CodeSetSchema
PUT    /codesets/{codeset_id}/schema               обновить schema (создаёт major-bump в новой версии)
```

#### Authoring (write только Author с правами в домене)

```
GET    /codesets/{codeset_id}/versions             список всех версий
POST   /codesets/{codeset_id}/versions             создать новую draft из последней published
GET    /versions/{version_id}                      метаданные версии
GET    /versions/{version_id}/items                items версии (paginated)
POST   /versions/{version_id}/items                добавить item
PATCH  /versions/{version_id}/items/{key}          обновить item (только в DRAFT)
DELETE /versions/{version_id}/items/{key}          удалить item (только в DRAFT)
POST   /versions/{version_id}/items/bulk           bulk add/update (CSV/JSON payload)
GET    /versions/{version_id}/diff?from={ver}      diff с другой версией
```

#### Workflow

```
POST   /versions/{version_id}/transitions          { "to": "IN_REVIEW", "comment": "..." }
GET    /versions/{version_id}/history              история переходов статусов
GET    /tasks/my                                   мои задачи на ревью/approve
```

#### Distribution (read-only, для consumer'ов)

```
GET    /rdm/{domain}/{codeset}/items
       ?version=published|<semver>
       &as_of=<ISO date>            (effective time)
       &knowledge_as_of=<ISO date>  (system time, bitemporal)
       &lang=ru|en
       &page=1&size=1000

GET    /rdm/{domain}/{codeset}/export
       ?version=...&format=csv|json|parquet|xlsx

GET    /rdm/{domain}/{codeset}/lookup/{key}        быстрый lookup одного item
```

#### Webhooks (для OM → RDM)

```
POST   /webhooks/om/ownership                      приём ownership events от OM
                                                   HMAC-проверка X-OM-Signature header
```

#### Webhooks subscriptions (для RDM → consumer'ов)

```
GET    /subscriptions                              список зарегистрированных webhook'ов
POST   /subscriptions                              { "url": "...", "filter": {...}, "secret": "..." }
DELETE /subscriptions/{id}
```

OpenAPI 3.1 спецификация генерируется из JSON Schema автоматически.

### 3.6. Ingestion-коннектор `om-rdmmesh-source`

**Отдельный артефакт**, не часть `rdmmesh-service`. Живёт в репозитории `om-rdmmesh-source`, деплоится в OM Airflow.

```
om-rdmmesh-source/
├── pyproject.toml
├── src/metadata/ingestion/source/database/rdmmesh/
│   ├── connection.py     # OIDC client_credentials к Keycloak (service account rdmmesh-bot)
│   ├── client.py         # клиент REST API rdmmesh
│   ├── metadata.py       # main source class — yield CreateTableRequest + AddColumnRequest
│   └── models.py         # Pydantic из rdmmesh JSON Schema (общая codegen)
└── tests/
```

**Маппинг сущностей:**

| rdmmesh | OM |
|---|---|
| Domain | Domain (matched by name) |
| CodeSet | Table (FQN: `rdmmesh.<domain_name>.<codeset_name>`) |
| CodeSetSchema attribute | Column |
| CodeSet.description, tags | Table.description, tags |
| CodeSet.last_published_version | Table.version (string) |

**Не передаётся ingestion'ом:** owners, experts, reviewers (это назначается в OM, и течёт обратно в RDM через webhook).

### 3.7. Нефункциональные требования

| Категория | Требование |
|---|---|
| **Производительность** | REST `lookup` p95 < 50 ms, `items` (1000 records) p95 < 500 ms |
| **Доступность** | 99.5% (рабочее время), planned maintenance вне рабочих часов |
| **RPO/RTO** | RPO ≤ 15 мин (PITR через `pg_basebackup` + WAL), RTO ≤ 1 час |
| **Backup** | Ежесуточный full + continuous WAL archiving, retention 7 лет (регуляторное) |
| **Безопасность** | TLS 1.3 везде, JWT валидация на каждом запросе, HMAC на всех webhooks, secrets в Vault/SOPS |
| **Audit retention** | 7 лет для `audit_log`, 10 лет для DEPRECATED published-версий (регуляторное IFRS9) |
| **Локализация** | UI: ru/en. Labels CodeItem: ru/en одновременно (фолбэк ru → en если ru пустой) |
| **Совместимость браузеров** | Chrome/Edge последние 2 версии, Firefox последние 2 версии |

### 3.8. Безопасность и compliance

- **Подпись approver'а**: при `OWNER_APPROVED` пишем `signature = HMAC_SHA256(version_content_hash || om_user_id || ISO8601_timestamp, server_secret)`. `version_content_hash` = SHA-256 от детерминированной (отсортированной по ключам) JSON-сериализации snapshot. Любой потребитель может перепроверить подлинность через `GET /versions/{id}/verify`.
- **Append-only audit**: таблица `audit_log` без UPDATE/DELETE. БД-роль приложения имеет только `INSERT` на эту таблицу (`REVOKE UPDATE, DELETE`). Параллельный сток в S3 (immutable bucket) — V2.
- **Reproducibility**: REST `?version=published&as_of=<date>&knowledge_as_of=<date>` обязан вернуть **ровно тот** snapshot, который видел consumer на эту пару дат.
- **No-bypass workflow**: даже Admin не может провести `WorkflowPort.transition` без выполнения правил state machine. Hotfix — отдельный шаблон, требующий пост-факт ревью.
- **Self-approval prevention**: проверка `created_by ≠ approved_by`, `created_by ≠ reviewed_by`, `reviewed_by ≠ approved_by` на уровне state machine.

---

## 4. Архитектура rdmmesh

### 4.1. Контекстная диаграмма (C4 уровень 1)

```
┌──────────────────────────────────────────────────────────────────┐
│                                                                  │
│   [AD Domain Controller] ──LDAP/Kerberos── [Keycloak]            │
│                                                │                 │
│                                                │ OIDC            │
│                                                │                 │
│         ┌──────────────────────────────────────┼──────────┐      │
│         │                                      │          │      │
│         ▼                                      ▼          ▼      │
│  [OpenMetadata]                         [rdmmesh-ui][rdmmesh-api]│
│         │                                                 │      │
│         │                                                 │      │
│         │ Event Subscription webhook                      │      │
│         │ (owners, experts, reviewers)                    │      │
│         ▼                                                 │      │
│  [/webhooks/om/ownership endpoint в rdmmesh] ─────────────┘      │
│         ▲                                                        │
│         │                                                        │
│  [om-rdmmesh-source] ──REST pull── [rdmmesh-api]                 │
│  (Python, в OM Airflow)                                          │
│         │                                                        │
│         │ создаёт Tables                                         │
│         ▼                                                        │
│  [OpenMetadata]                                                  │
│                                                                  │
│                                                                  │
│  [Consumer-системы:                                              │
│   Risk-engine, IdM/IGA, BI, dbt, ETL]                            │
│         ▲                                                        │
│         │                                                        │
│         │ REST + Webhooks                                        │
│         │                                                        │
│  [rdmmesh-api]                                                   │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

### 4.2. Контейнерная диаграмма (C4 уровень 2)

```
┌─────────────────────────────────────────────────────────────────────┐
│                       rdmmesh-service (JVM)                         │
│                                                                     │
│  ┌────────┐ ┌───────────┐ ┌──────────┐ ┌────────────┐ ┌──────────┐  │
│  │catalog │ │ authoring │ │ workflow │ │ publishing │ │distrib.  │  │
│  └───┬────┘ └─────┬─────┘ └────┬─────┘ └──────┬─────┘ └────┬─────┘  │
│      │            │            │              │            │        │
│      └─────┬──────┴────────────┴──────────────┘            │        │
│            │                                               │        │
│            │ ┌────────┐ ┌──────────┐ ┌────────┐            │        │
│            │ │identity│ │ownership │ │ audit  │            │        │
│            │ └───┬────┘ └────┬─────┘ └───┬────┘            │        │
│            │     │           │           │                 │        │
│            └─────┴───────────┴───────────┘                 │        │
│                          │                                 │        │
│                          ▼                                 ▼        │
└──────────────────────────┬─────────────────────────────────┬────────┘
                           │                                 │
                           ▼                                 ▼
                   ┌───────────────┐               ┌──────────────────┐
                   │ PostgreSQL 16 │               │ Webhook delivery │
                   │ schemas:      │               │ (out-of-process  │
                   │ catalog,      │               │  worker, retry)  │
                   │ authoring,    │               └──────────────────┘
                   │ workflow,     │
                   │ publishing,   │
                   │ identity,     │
                   │ ownership,    │
                   │ audit         │
                   └───────────────┘
```

### 4.3. Ключевые архитектурные решения (ADR-style)

#### ADR-001. Modular monolith вместо микросервисов

**Контекст:** На старте — одна команда, два пилотных домена.
**Решение:** Один деплой, восемь Maven-модулей с строгой изоляцией.
**Обоснование:** Микросервисы дают operational overhead, который не оправдан на этих объёмах. Модульный монолит легко расщепляется на сервисы потом — самый вероятный кандидат `distribution` (read-нагрузка).
**Контроль:** ArchUnit-тесты на запрет cross-imports.

#### ADR-002. Schema-first с JSON Schema

**Контекст:** Согласованность контрактов между backend, frontend, ingestion-коннектором.
**Решение:** Все сущности описаны JSON Schema в `rdmmesh-spec/schema/`. Кодогенерация Java POJO, TypeScript типов, Python Pydantic.
**Обоснование:** Подход OpenMetadata. Один источник истины. Переход к OpenAPI 3.1 — автоматический.

#### ADR-003. Postgres FTS вместо Elasticsearch в MVP

**Контекст:** Объёмы небольшие, дополнительная инфраструктура — overhead.
**Решение:** Postgres FTS со словарями `russian` + `english`, `pg_trgm` для typo-tolerant. Скрыто за `SearchPort`.
**Trade-off:** Слабее ES в фасетах/синонимах/агрегациях. Когда понадобится — подменим адаптер.

#### ADR-004. Enum state machine вместо Flowable

**Контекст:** Один шаблон workflow на MVP, кастомизация — V2.
**Решение:** ~200 строк собственного кода с Map<Status, Set<Status>> ALLOWED + `WorkflowPort.transition()`.
**Trade-off:** Кастомные BPMN per Domain невозможны без миграции на Flowable. Подменяется адаптером без рефакторинга бизнес-логики.

#### ADR-005. Слабая связанность с OpenMetadata

**Контекст:** RDM не должен валиться при недоступности OM и наоборот.
**Решение:**
- RDM не вызывает OM API в синхронных бизнес-процессах.
- Регистрация в OM — только через ingestion (pull со стороны OM).
- Ownership из OM — асинхронный webhook на endpoint RDM.
- Bootstrap-период покрыт provisional ownership.
**Обоснование:** Reliability + чистая граница ответственности. OM — governance hub, RDM — lifecycle справочников.

#### ADR-006. Bitemporal в схеме данных с первого дня

**Контекст:** Регуляторные требования IFRS9 на reproducibility.
**Решение:** Колонки `effective_*` и `system_*` в `code_item` и `code_set_version` с MVP. UI может их не показывать вначале, но данные пишутся.
**Обоснование:** Добавлять задним числом — болезненно. Накладные расходы на старте — минимальные (4 timestamp-колонки + GiST-индекс).

#### ADR-007. Composite keys в схеме данных с первого дня

**Контекст:** Risk/IFRS9 (segment×rating) и Security/Access Matrix (position×system) требуют N-мерные ключи.
**Решение:** `code_item.key_parts JSONB` (массив значений ключевых частей). KeySpec в CodeSet описывает имена и типы частей.
**Обоснование:** Универсальная модель, перекрывает одиночные ключи (массив длины 1) и матрицы.

#### ADR-008. Бизнес-домены и ownership — мастер в OpenMetadata, не в RDM

**Контекст:** OM — корпоративный governance hub. Бизнес-домены (со своими названиями, кодами, иерархией, владельцами) и ownership на data assets живут в OM. RDM как lifecycle-инструмент справочников не должен дублировать определение этих сущностей — иначе появятся два источника истины с расхождениями.

**Решение:**
- В UI RDM нет экранов создания/редактирования domain'а или назначения owner'а.
- `catalog.domain` — асинхронный mirror, заполняется webhook'ом OM Event Subscription для `entityType=domain`.
- `ownership.rdm_asset_ownership` — асинхронный mirror, заполняется webhook'ом для `entityType=table` (FQN `rdmmesh.*`).
- В форме создания CodeSet нет полей owner (provisional = creator) и domain свободного ввода (выбор только из mirror'а). Реальный owner приходит позже через webhook.
- Bootstrap-режим (только `RDM_ADMIN`, см. §2.4): техническая мера до выхода webhook-канала на стационарный режим; идемпотентен с последующим OM-webhook'ом по `om_domain_id`.

**Trade-off:** UX-неудобство в bootstrap-периоде (новый domain в OM появляется в RDM только после webhook'а, типичная задержка — секунды) — компенсируется баннером и отсутствием блокировки publish'а на provisional-owner'е.

### 4.4. Маппинг бизнес-сценариев на архитектуру

#### Сценарий «Risk steward вносит изменения в IFRS9 PD-матрицу»

1. UI: Author открывает CodeSet `ifrs9_pd_matrix`, создаёт draft `v3.2.0-draft` (REST `POST /codesets/.../versions`).
2. UI: Author редактирует ячейки матрицы в TanStack Table grid (REST `PATCH /versions/.../items/{key}`). `key_parts = ["RETAIL", "BB", "12M"]`, `attributes = {pd: 0.0234}`.
3. Authoring валидирует attributes по CodeSetSchema. Запись в Postgres schema `authoring`.
4. UI: «Сравнить с published» → REST `GET /versions/{draft_id}/diff?from=v3.1.0` → diff отображается в side-by-side.
5. UI: Author подаёт на ревью → REST `POST /versions/.../transitions {"to": "IN_REVIEW"}`. Authoring → WorkflowPort.transition. Запись в schema `workflow`. Audit-запись в schema `audit`.
6. Steward (определён через `rdm_asset_ownership` для CodeSet) видит задачу в «My Tasks» (REST `GET /tasks/my`).
7. Steward делает approve. WorkflowPort.transition → STEWARD_APPROVED.
8. Owner (определён через `rdm_asset_ownership`) делает approve → OWNER_APPROVED → автоматический publish.
9. Publishing создаёт snapshot, считает content_hash, подписывает HMAC, эмитит событие.
10. OutboundPort рассылает webhook в Risk-engine. Audit-запись.
11. В следующий цикл ingestion (час) OM забирает обновлённый CodeSet, обновляет Table.

#### Сценарий «Создан новый бизнес-домен в OpenMetadata»

1. Data Governance team в UI OpenMetadata создаёт Domain `treasury` (name=`treasury`, displayName=`Treasury Department`, owner=@ivanov).
2. OM эмитит `ENTITY_CREATED { entityType: domain, fqn: "treasury", name, displayName, description, owners: [ivanov], ... }`.
3. OM Event Subscription пушит JSON в `https://rdm.bank/webhooks/om/ownership`.
4. RDM ownership-модуль валидирует HMAC, парсит payload, видит `entityType=domain`.
5. `UPSERT INTO catalog.domain (om_domain_id, name, display_name, description, ...)`. В этой же транзакции `UPSERT INTO rdm_asset_ownership (asset_id=domain_id, asset_type=DOMAIN, om_user_id=ivanov_uuid, role=OWNER)`.
6. Через несколько секунд новый domain появляется в выпадающем списке формы «Создать CodeSet» — Author из домена Treasury может начать заводить справочники.

#### Сценарий «Назначен новый Domain Owner для домена Risk»

1. Admin OM назначает @petrov владельцем Domain `risk` в UI OpenMetadata.
2. OM эмитит `ENTITY_UPDATED { entityType: domain, fields: [owners], owners: [+petrov] }`.
3. OM Event Subscription пушит JSON в `https://rdm.bank/webhooks/om/ownership`.
4. RDM ownership-модуль валидирует HMAC-подпись, парсит payload.
5. Lookup `petrov` в `rdm_user_mapping` (если нет — REST к OM API за `User.id` по `name`).
6. UPSERT в `rdm_asset_ownership (asset_id=domain_id, asset_type=DOMAIN, om_user_id=petrov_uuid, role=OWNER, is_provisional=false)`.
7. Permission cache invalidated. Petrov видит задачи Owner для всех CodeSet домена Risk.

---

## 5. План работ

### 5.1. Эпики

| Epic | Описание | Зависимости |
|---|---|---|
| **E1. Foundation** | Bootstrap репо, pom-структура 8 модулей, JSON Schema ядра, кодогенерация, Flyway-миграции, Dropwizard skeleton, Docker Compose | — |
| **E2. Identity** | Keycloak setup, JWT валидация, маппинг AD-групп → ролей, `rdm_user_mapping` | E1 |
| **E3. Catalog & Schema** | CodeSet CRUD, CodeSetSchema CRUD, валидация, REST API, `catalog` модуль | E1 |
| **E4. Authoring** | CodeSetVersion CRUD, CodeItem CRUD, draft management, bulk import (CSV), валидация по schema, diff между версиями, `authoring` модуль | E3 |
| **E5. Workflow** | Enum state machine, переходы, проверка self-approval, задачи, `workflow` модуль | E4, E2, E7 |
| **E6. Publishing** | Snapshot creation, content hash, HMAC signature, эмиссия события publish, `publishing` модуль | E5 |
| **E7. Ownership** | Webhook receiver `/webhooks/om/ownership`, HMAC валидация, маппинг ролей, provisional ownership, `ownership` модуль | E2, E3 |
| **E8. Distribution** | Read-only REST для consumer'ов с `as_of`/`knowledge_as_of`, bulk export (CSV/JSON), `distribution` модуль | E6 |
| **E9. Outbound** | Webhook subscriptions CRUD, доставка с retry/backoff, идемпотентность, HMAC | E6 |
| **E10. Audit** | Append-only `audit_log`, GRANT INSERT ONLY, listener на event-bus, `audit` модуль | E1 |
| **E11. UI** | React-приложение, экраны Catalog/CodeSet/Editor/Versions/Diff/Tasks, OIDC, i18n ru+en | E3, E4, E5, E8 |
| **E12. Ingestion-коннектор** | Python пакет `om-rdmmesh-source`, маппинг в OM Tables, тесты | E3 (REST API стабильно) |
| **E13. Bitemporal & Hierarchy** | Closure table, GiST-индексы, API параметры `as_of`/`knowledge_as_of`, иерархический tree-редактор | E4, E8, E11 |
| **E14. Compliance hardening** | Audit-export, verify-endpoint для подписи, no-bypass проверки, security review | E6, E10 |

### 5.2. Поэтапная дорожная карта

#### MVP (~3 месяца) — пилот Risk/IFRS9

**Цель:** Пилотный домен Risk с одним справочником `ifrs9_stages` и одной матрицей `ifrs9_pd` в продуктиве.

Эпики: E1, E2, E3, E4, E5, E6, E7, E10, E11 (минимальный UI), E13 (bitemporal без UI-параметров), E14 (signature + audit).

Артефакты к выпуску:
- Развёрнутый `rdmmesh-service` в продуктивном кластере.
- Postgres 16 с PITR.
- Keycloak с подключённым AD и двумя клиентами (rdmmesh + OM).
- Минимальный UI: Catalog tree, CodeSet view, Items grid editor, Versions list, version Diff, My Tasks.
- REST API по контракту §3.5 (без `as_of`-параметров на распределение, добавим в V1).
- Один зашитый workflow-шаблон (4-eyes).
- Webhook receiver для OM ownership.
- Append-only audit + HMAC signatures.

**Что осознанно отложено:**
- Ingestion-коннектор `om-rdmmesh-source` (CodeSet будут регистрироваться вручную в OM на пилоте; коннектор — V1).
- Outbound webhooks для consumer'ов (на пилоте Risk-engine читает напрямую через REST).
- Bulk-операции в одном draft.
- Hierarchy-редактор (для IFRS9 матриц достаточно flat grid).

#### V1 (+3 месяца) — масштабирование на Security и enterprise

Эпики: E8 (distribution с as_of), E9 (outbound), E12 (ingestion в OM), E13 (полная иерархия с UI), bulk-операции из BR-16, оставшиеся справочники IFRS9, подключение домена Security/Access Matrix.

Артефакты:
- Полный `om-rdmmesh-source` коннектор, развёрнут в OM Airflow.
- Webhook subscriptions с retry/backoff для consumer-систем.
- Closure table + tree-редактор для Security/Access Matrix.
- Bulk-операции (новая система → дефолтные права для всех позиций).
- Distribution API с полной поддержкой bitemporal параметров.

#### V2 (+6 месяцев) — расширение и зрелость

Эпики:
- BR-17: Emergency hotfix workflow.
- BR-18: Custom BPMN per Domain (миграция на Flowable за `WorkflowPort`, без рефакторинга бизнес-логики).
- BR-19: Kafka outbound для streaming (за `OutboundPort`).
- Полнотекстовый поиск через Elasticsearch (за `SearchPort`), если объёмы вырастут.
- dbt source generation, Trino FDW.
- Дополнительные домены банка (Compliance/AML, Treasury, …).
- Audit-export в S3 immutable bucket.

### 5.3. Открытые риски и их митигация

| Риск | Воздействие | Митигация |
|---|---|---|
| Задержка ingestion в OM создаёт провал в ownership | Provisional owner может оказаться неактуальным неделями | Баннер в UI, уведомление admin'у через 24 часа provisional |
| Self-approval через AD-группы | Compliance-нарушение | Жёсткая проверка `created_by ≠ approved_by ≠ reviewed_by` на уровне state machine, покрытие тестами |
| Ingestion-коннектор может пропустить удаление CodeSet | Stale Tables в OM | Soft-delete в RDM + period reconciliation в коннекторе |
| Webhook от OM может потеряться (network issues) | Stale ownership в RDM | Подписать на `RETRY` events в OM + nightly reconciliation job (full pull owners из OM API) |
| Большой draft (миллионы records) — OOM в Java heap | Сервис падает | Streaming JSON parse, page-by-page bulk import |
| Конфликт двух Author'ов в одном draft | Last-write-wins, потеря изменений | Optimistic locking через `version` колонку на CodeItem, UI-уведомление о конфликте |
| Старые DEPRECATED версии раздувают БД | Performance degradation в долгосрочной перспективе | Партиционирование `code_item` по `version_id` (LIST) — pluggable, миграцией без рефакторинга |
| Keycloak становится SPOF для двух систем | Полный outage аутентификации | HA Keycloak (минимум 2 ноды), кэш JWKS в обоих клиентах |

### 5.4. Метрики готовности (Definition of Done для MVP)

- [ ] Все эпики MVP закрыты.
- [ ] Покрытие unit-тестами ≥ 80% для модулей `catalog`, `authoring`, `workflow`, `publishing`.
- [ ] Интеграционные тесты на полный 4-eyes workflow проходят в CI.
- [ ] ArchUnit-тесты на изоляцию модулей зелёные.
- [ ] Security review пройден (OWASP Top 10, проверка JWT, HMAC, no-bypass workflow).
- [ ] Один справочник IFRS9 в продуктивном использовании Risk-engine'ом.
- [ ] Ownership webhook работает end-to-end с реальным OM.
- [ ] Backup/restore процедуры протестированы.
- [ ] Документация для пользователей (Author, Steward, Owner) написана.
- [ ] Runbook для оператора (как перезапустить, где смотреть логи, как восстановить из backup) написан.

---

## Приложение A. Структура репозиториев

```
rdmmesh/                          (главный репо, JVM-сервис + UI + спека)
├── SPEC.md                       (этот документ)
├── pom.xml                       (parent)
├── rdmmesh-spec/                 (JSON Schema, codegen-конфиги)
├── rdmmesh-api/                  (общие интерфейсы Port, DTO)
├── rdmmesh-catalog/
├── rdmmesh-authoring/
├── rdmmesh-workflow/
├── rdmmesh-publishing/
├── rdmmesh-distribution/
├── rdmmesh-identity/
├── rdmmesh-ownership/
├── rdmmesh-audit/
├── rdmmesh-app/                  (Dropwizard Application, объединяет всё)
├── rdmmesh-ui/                   (React + TS + Vite)
├── bootstrap/sql/migrations/     (Flyway)
├── docker/                       (Dockerfile, docker-compose.yml)
├── helm/                         (Helm chart для production)
└── docs/                         (ADR, диаграммы, runbooks)

om-rdmmesh-source/                (отдельный репо, Python ingestion-коннектор)
├── pyproject.toml
├── src/metadata/ingestion/source/database/rdmmesh/
└── tests/
```

## Приложение B. Глоссарий

| Термин | Определение |
|---|---|
| **CodeSet** | Бизнес-справочник, набор связанных CodeItem с общей структурой |
| **CodeSetSchema** | JSON Schema, описывающая структуру атрибутов CodeItem конкретного CodeSet |
| **CodeSetVersion** | Версия CodeSet (DRAFT или published-snapshot) |
| **CodeItem** | Одна запись справочника (например, страна `KZ` со всеми атрибутами) |
| **KeySpec** | Описание ключа CodeSet: одиночный или composite (N частей) |
| **Bitemporal** | Двухосевая модель времени: effective (бизнес) + system (системное) |
| **Effective time** | Когда запись действует/действовала с точки зрения бизнеса |
| **System time** | Когда запись была известна системе |
| **Snapshot** | Полная копия всех CodeItem версии, иммутабельная после publish |
| **Provisional owner** | Временный владелец (создатель), действует до прихода реального owner из OM |
| **4-eyes** | Принцип «четырёх глаз»: Author + Steward + Owner, без права self-approval |
| **HMAC signature** | Криптографическая подпись approver'а, удостоверяет подлинность published-версии |
| **Closure table** | Структура для быстрого обхода иерархий (ancestor, descendant, depth) |
| **Bounded context** | Изолированный модуль с собственной моделью, явным интерфейсом наружу |
| **Port / Adapter** | Hexagonal architecture: Port = интерфейс модуля, Adapter = конкретная реализация |
| **Ingestion** | Процесс забора метаданных в OpenMetadata (pull со стороны OM) |
