# Handoff — Эпик E12 (Ingestion-коннектор `om-rdmmesh-source`)

> **Аудитория документа.** AI-агенты и инженеры, подключающиеся к проекту после
> E12. Документ самодостаточен — переписки и контекста предыдущей сессии у вас
> нет, всё что нужно — здесь, в [`SPEC.md`](../../SPEC.md) §3.6,
> [`E1-foundation.md`](E1-foundation.md), [`E2-identity.md`](E2-identity.md),
> [`E3-catalog.md`](E3-catalog.md), [`E4-authoring.md`](E4-authoring.md),
> [`E6-publishing.md`](E6-publishing.md) и [`E7-ownership.md`](E7-ownership.md).
>
> **Дата handoff'а.** 2026-05-11.
> **Состояние.** E12 закрыт по содержанию SPEC §5.1 на уровне **скелета,
> контракта и unit-тестов**. Pytest — **45/45 passed** in 0.18s (6 client с
> responses-mock'ами + 12 mapping + 27 connection options). Ruff — All checks
> passed. End-to-end smoke против реального OM Airflow **не прогонялся** —
> требует следующей сессии с поднятым OM-стэком (см. §6). Pip-пакет
> `om-rdmmesh-source` создан, интегрируется в **vanilla-OM** через
> `CustomDatabaseConnection` + `sourcePythonClass` — **никаких правок в
> openmetadata-spec не вносится** (см. §1.4 — это архитектурный инвариант,
> зафиксирован в memory как feedback).
>
> **Следующий эпик:** E13 (Bitemporal & Hierarchy). Указатели — в §5.

---

## 0. TL;DR за 30 секунд

- **Новый артефакт** — `/home/daurena2609/projects/rdmmesh/om-rdmmesh-source/`,
  pip-пакет (namespace package по PEP 420). 7 файлов кода + 3 тестовых +
  пример workflow.yaml. Реализует OM `DatabaseServiceSource` для rdmmesh REST API.
- **Vanilla-OM подход.** OM-репозиторий **не патчится**. Коннектор интегрируется
  через стандартный `CustomDatabaseConnection` (есть в OM из коробки) с
  `sourcePythonClass=metadata.ingestion.source.database.rdmmesh.metadata.RdmmeshSource`.
  В `connectionOptions: Map<String,String>` кладутся hostPort, keycloakIssuerUri,
  clientId, clientSecret и т.д. — наш `connection.py` парсит их сам.
- **Маппинг**: rdmmesh Domain → OM `DatabaseSchema`, CodeSet → `Table` (FQN
  `rdmmesh.default.<domain>.<codeset>`), CodeSetSchema → `Column[]` (включая
  key parts с `Constraint.NOT_NULL`). Owners/experts/reviewers **не**
  переносятся (SPEC §2.4 — назначаются в OM, обратно текут через E7 webhook).
- **Auth**: Keycloak `client_credentials` (`rdmmesh-backend` client из E2),
  Bearer JWT с in-memory кэшем + автоматический re-auth при 401. Token TTL
  обрезан на 60s до истечения, чтобы не получить 401 в середине long-running
  ingestion'а.
- **Discovery в OM**: через `CustomDatabaseConnection.sourcePythonClass` (см.
  `metadata/workflow/ingestion.py:200` → `import_from_module(...)`). Это значит
  service-spec.py больше не критичен (OM импортирует напрямую `RdmmeshSource`).
  Оставлен на случай если OM поменяет discovery в будущем.
- **Тесты**: 45 unit'ов (6 client + 12 mapping + 27 connection options). **Все
  прогоняются без openmetadata-ingestion** — нужны только `requests`, `responses`,
  `pydantic`, `pytest`. Прогнаны в Python 3.12.13: 45 passed in 0.18s.

---

## 1. Что сделано

### 1.1. Структура pip-пакета `om-rdmmesh-source`

```
rdmmesh/om-rdmmesh-source/
├── pyproject.toml            ← setuptools, find_namespace_packages, Pydantic 2,
│                                requests, pytest, responses, ruff, mypy
├── README.md                 ← инструкция установки в OM venv, формат workflow.yaml
├── .gitignore
├── examples/
│   ├── README.md             ← как готовить и запускать workflow
│   └── rdmmesh-workflow.yaml ← заготовка для `metadata ingest -c`
├── src/metadata/ingestion/source/database/rdmmesh/
│   ├── __init__.py           ← пустой (concrete package, не namespace на этом уровне)
│   ├── models.py             ← Pydantic v2 wire-формат rdmmesh: Domain/CodeSet/Schema/Version
│   ├── client.py             ← RdmmeshClient: Keycloak client_credentials + REST endpoints
│   ├── connection.py         ← парсер connectionOptions + SHA-256-cache клиента
│   ├── mapping.py            ← pure-helpers map_jsonschema_type / build_description (БЕЗ OM SDK)
│   ├── metadata.py           ← RdmmeshSource(DatabaseServiceSource) — основная логика
│   └── service_spec.py       ← ServiceSpec = DefaultDatabaseSpec(metadata_source_class=RdmmeshSource)
└── tests/
    ├── __init__.py
    ├── test_client.py             ← 6 unit'ов на client (responses lib)
    ├── test_mapping_pure.py       ← 12 unit'ов на pure-helpers из mapping.py
    └── test_connection_options.py ← 27 unit'ов на парсер connectionOptions
```

Структура каталогов под `src/metadata/ingestion/source/database/` намеренно
повторяет namespace OM — все папки до `rdmmesh/` это namespace-packages (PEP 420,
без `__init__.py`). Это позволяет OM найти наш модуль через обычный Python import
после `pip install`, без вмешательства в их `setup.py`.

### 1.2. Контракт rdmmesh REST → OM mapping

| rdmmesh                                    | OpenMetadata                                              |
|--------------------------------------------|-----------------------------------------------------------|
| `/api/v1/domains` → Domain                 | `DatabaseSchema(name=<domain.name>)` под synthetic `Database "default"` |
| Domain.displayName                         | DatabaseSchema.displayName                                |
| Domain.description                         | DatabaseSchema.description (Markdown)                     |
| `/api/v1/codesets/by-domain/{id}` → CodeSet| `Table(name=<codeset.name>, tableType=Regular)`           |
| CodeSet.description + version + key_spec   | Table.description (Markdown с тремя секциями)             |
| `/api/v1/codesets/{id}/schema` → JSON Schema | Table.columns (см. §1.3)                                |
| `/api/v1/versions/by-codeset/{id}` (PUBLISHED, latest) | включается в Table.description как `_Published version:_ \`X.Y.Z\`` |
| CodeSet.deleted_at IS NOT NULL             | пропускаем в ingestion'е → OM делает `markAsDeleted` через `markDeletedTables` source config |
| CodeSet.tags                               | (не передаём на MVP — debt §4)                            |

#### FQN

SPEC §3.6 говорит про 3-сегментный FQN `rdmmesh.<domain>.<codeset>`. Реальный
OM-FQN 4-сегментный (`service.database.schema.table`), поэтому коннектор
вставляет константный `database="default"`. Полный FQN получается
`rdmmesh.default.<domain>.<codeset>` (где `service="rdmmesh"` задаётся в
workflow.yaml через `serviceName`).

В OM UI пользователь видит навигацию: Service `rdmmesh` → Database `default` →
Schema `<domain>` → Table `<codeset>` — то же дерево, что и SPEC задумывает.

### 1.3. JSON Schema → OM Column

`_build_columns(schema_doc, codeset)`:

1. **Ключевые части** (`codeset.key_spec.parts`) — отдельные Column'ы в начале
   таблицы, всегда `Constraint.NOT_NULL`. Маппинг типов через
   `mapping.map_key_part_type(...)`:
   - `STRING` → `STRING`
   - `INTEGER` → `BIGINT`
   - `NUMBER` → `DOUBLE`
   - `BOOLEAN` → `BOOLEAN`
   - `DATE` → `DATE`
   - `DATETIME` → `DATETIME`
   - `UUID` → `UUID`
   - default → `STRING`
2. **Атрибуты CodeItem** (`schema_doc.json_schema.properties`) — Column'ы после
   key parts. Маппинг через `mapping.map_jsonschema_type(...)`:
   - `{"type":"string"}` → `STRING`
   - `{"type":"string", "format":"date-time"}` → `DATETIME`
   - `{"type":"string", "format":"date"}` → `DATE`
   - `{"type":"string", "format":"uuid"}` → `UUID`
   - `{"type":"integer"}` → `BIGINT`
   - `{"type":"number"}` → `DOUBLE`
   - `{"type":"boolean"}` → `BOOLEAN`
   - `{"type":"object"}` → `STRUCT` + рекурсивное построение `children`
   - `{"type":"array"}` → `ARRAY` (без element-type — debt §4)
   - `enum: [...]` (любой тип) → `ENUM`
   - `["string","null"]` (nullable union) → берём первый не-`null`
   - **required** в JSON Schema → `Constraint.NOT_NULL`
   - **description** → Column.description (Markdown)

Глубокая рекурсия в `STRUCT.children` — упрощённо, на одну глубину (массивы
вложенных объектов не разворачиваются). V1+ если бизнес попросит.

### 1.4. Vanilla-OM интеграция (ключевой инвариант)

**Принцип:** rdmmesh должен работать с любым vanilla-OM — без правок
`openmetadata-spec`, без commit'ов в open-metadata/OpenMetadata. Любая такая
правка создаёт долг при upstream-апгрейдах и блокеры на prod-deploy с managed-OM.

Реализуется через готовые механизмы расширения OM:

1. **`CustomDatabaseConnection`** (`openmetadata-spec/.../database/customDatabaseConnection.json`)
   — generic тип сервиса, который УЖЕ в OM. Поля:
   - `type: CustomDatabase` (enum value, в `databaseServiceType.enum`).
   - `sourcePythonClass: string` — dotted-path к нашему Source class.
   - `connectionOptions: Map<String,String>` — свободный набор параметров.
   - Стандартные `schemaFilterPattern` / `tableFilterPattern` / `supportsMetadataExtraction`.

2. **Discovery** в OM workflow runner (см.
   `ingestion/src/metadata/workflow/ingestion.py:200`):
   ```python
   import_from_module(self.config.source.serviceConnection.root.config.sourcePythonClass)
   ```
   OM сам импортирует `metadata.ingestion.source.database.rdmmesh.metadata.RdmmeshSource`
   и вызывает `RdmmeshSource.create(config, metadata)`.

3. **Парсинг `connectionOptions`** — наш собственный код в
   `connection.py::_make_client(...)`. Все значения — строки (требование OM),
   парсятся в типизированные `RdmmeshClient`-аргументы:
   - `hostPort` (обязательное)
   - `keycloakIssuerUri` (обязательное)
   - `clientId` (по умолчанию `rdmmesh-backend`)
   - `clientSecret` (обязательное)
   - `requestTimeoutSeconds` (str → int, опционально)
   - `verifySSL` (`"true"`/`"false"`/`"1"`/`"0"` → bool, по умолчанию `true`)

**Trade-offs vanilla-OM подхода:**
- ➖ В OM UI коннектор появляется в категории «Custom Database», без nice
  form-полей под наши настройки — конфигурация только через workflow.yaml.
- ➖ `clientSecret` хранится в OM-БД как plain Map-value (encrypt-at-rest зависит
  от OM-конфига / OM Secret Manager).
- ➕ Любой апгрейд OM работает без правок в нашем коде или в OM-репо.
- ➕ Коннектор устанавливается в любой managed-OM Airflow одним `pip install`.
- ➕ Нет необходимости PR'ить в upstream open-metadata/OpenMetadata.

Этот инвариант зафиксирован в feedback-memory
(`reference_openmetadata_source.md`/`feedback_no_om_modifications.md`) —
не нарушать в будущих эпиках.

### 1.5. Auth flow в `RdmmeshClient`

```
RdmmeshSource.__init__
  ↓
get_connection(service_connection)              ← SHA-256 кэш по connectionOptions
  ↓
RdmmeshClient.__init__                          ← lazy, без HTTP-call'ов
  ↓
[первый list_domains/list_codesets/...]
  ↓
_ensure_token():
  if не было токена OR < now: POST {issuer}/protocol/openid-connect/token
                              grant_type=client_credentials
                              client_id=rdmmesh-backend
                              client_secret=<из connectionOptions>
  cache token + expires_at = now + (expires_in - 60s)
  ↓
GET {host}/api/v1/...   Authorization: Bearer <token>
  if 401: forced re-auth, retry один раз
  if 5xx: raise RdmmeshApiError
```

Cache — in-memory на жизнь процесса OM Airflow worker'а. Token TTL обычно 300s
в Keycloak dev — пересоздаётся каждый запуск ingestion'а.

---

## 2. Контракт

### 2.1. Workflow YAML (input)

Пример из `om-rdmmesh-source/examples/rdmmesh-workflow.yaml`:

```yaml
source:
  type: customdatabase
  serviceName: rdmmesh
  serviceConnection:
    config:
      type: CustomDatabase
      sourcePythonClass: metadata.ingestion.source.database.rdmmesh.metadata.RdmmeshSource
      connectionOptions:
        hostPort: http://localhost:8080
        keycloakIssuerUri: http://localhost:8090/realms/bank
        clientId: rdmmesh-backend
        clientSecret: dev-backend-secret
        requestTimeoutSeconds: "30"
        verifySSL: "false"
      schemaFilterPattern:
        includes: [".*"]
      tableFilterPattern:
        includes: [".*"]
  sourceConfig:
    config:
      type: DatabaseMetadata
      includeTables: true
      includeViews: false
      markDeletedTables: true
      markDeletedSchemas: true
sink:
  type: metadata-rest
  config: {}
workflowConfig:
  openMetadataServerConfig:
    hostPort: http://localhost:8585/api
    authProvider: openmetadata
    securityConfig:
      jwtToken: REPLACE_WITH_OM_BOT_JWT
```

### 2.2. Errors

| Условие                                     | Exception / behaviour                                                |
|---------------------------------------------|----------------------------------------------------------------------|
| Keycloak token endpoint вернул не-2xx       | `RdmmeshAuthError` (fail-fast при первом запросе)                    |
| rdmmesh REST вернул 401                     | один раз re-auth и retry; если снова 401 → `RdmmeshApiError`         |
| rdmmesh REST вернул 4xx (кроме 401)/5xx     | `RdmmeshApiError` → останавливает ingestion                          |
| `get_codeset_schema(...)` вернул 404        | yield `StackTraceError`, ingestion продолжается со следующим CodeSet |
| `CodeSet.deleted_at IS NOT NULL`            | пропускается на этапе `get_tables_name_and_type`                     |
| `Domain.deleted_at IS NOT NULL`             | пропускается на этапе `get_database_schema_names`                    |
| connection не `CustomDatabaseConnection`    | `InvalidSourceException` в `RdmmeshSource.create()`                  |
| Обязательное поле `connectionOptions.X` пусто/нет | `ValueError("connectionOptions.X не задан...")` в `_make_client`  |
| `requestTimeoutSeconds` не парсится как int | `ValueError("connectionOptions: ожидался int, получили '...'")`      |

---

## 3. Что осталось доделать в E12 — обязательно перед smoke

### 3.1. CRITICAL — установить коннектор в OM ingestion venv

```bash
# OM ingestion venv (как в /home/daurena2609/projects/OpenMetadata/ingestion)
cd /home/daurena2609/projects/OpenMetadata/ingestion
python3.10 -m venv .venv
source .venv/bin/activate
pip install -e ".[base]"                          # OM SDK с CLI `metadata`

# Наш коннектор
cd /home/daurena2609/projects/rdmmesh/om-rdmmesh-source
pip install -e ".[dev]"                           # 45 unit'ов + ruff/mypy

# Проверка discovery (без OM workflow):
python -c "from metadata.ingestion.source.database.rdmmesh.metadata import RdmmeshSource; print(RdmmeshSource)"
```

В отличие от первоначального плана E12 **regen Pydantic / Java POJO в OM
больше не нужен** — `CustomDatabaseConnection` уже есть в любом vanilla-OM.

### 3.2. Smoke (тот же чек-лист, что в handoff E11-* — не прогнан в этой сессии)

После §3.1:

```bash
# 1. Поднять полный backend (rdmmesh + keycloak + postgres)
cd /home/daurena2609/projects/rdmmesh
make up

# Создать данные для ingestion'а (E10 smoke даёт всё, что нужно):
# domain risk → codeset ifrs9_stages → DRAFT → 4-eyes → PUBLISHED 0.1.0

# 2. Поднять OM
cd /home/daurena2609/projects/OpenMetadata
docker compose -f docker/development/docker-compose.yml up -d

# 3. Получить bot-токен OM

# 4. Скопировать examples/rdmmesh-workflow.yaml → rdmmesh-workflow.local.yaml,
#    подставить hostPort/clientSecret/jwtToken и запустить:
cd /home/daurena2609/projects/rdmmesh/om-rdmmesh-source
metadata ingest -c examples/rdmmesh-workflow.local.yaml

# Ожидаемый вывод: Source Status processed > 0 (Database + Schemas + Tables)

# 5. В OM UI: Services → Databases → rdmmesh → default → risk → ifrs9_stages.
# Должна быть таблица с колонками key parts + stage (NOT_NULL, ENUM ["1","2","3"]).
# В Description — semver, hierarchy_mode, key_spec.
```

### 3.3. Прогнать unit-тесты

```bash
cd /home/daurena2609/projects/rdmmesh/om-rdmmesh-source
pytest                                  # 45 тестов, без OM SDK
pytest --cov=metadata.ingestion.source.database.rdmmesh
ruff check src/ tests/
```

В этой сессии все три прошли (Python 3.12.13 venv в `.venv/`):
- pytest: **45 passed in 0.18s**
- ruff: **All checks passed**

### 3.4. Согласовать env-var для OM bot-токена

В workflow.yaml сейчас `clientSecret: dev-backend-secret` — это dev fallback,
тот же что в E2 realm-bank.json. Prod-значение должно идти через OM Secret
Manager (см. open-question §7 п.3).

---

## 4. Технический долг и решения, повлиявшие на следующие эпики

| Что | Где | Когда снять / следующий шаг |
|---|---|---|
| **Vanilla-OM** — никаких правок в openmetadata-spec | `metadata.py`, `connection.py` | **Не снимать никогда.** Архитектурный инвариант, зафиксирован в memory. |
| `clientSecret` в `connectionOptions` хранится в OM-БД как plain Map-value | OM `CustomDatabaseConnection` | Зависит от OM Secret Manager (Vault/KMS) — отдельный issue для DevOps. На пилоте — dev-secret. |
| Owners/experts/reviewers НЕ передаются ingestion'ом | `metadata.py` | **Не снимать** — это правильное поведение по SPEC §2.4 (OM master; обратно через E7 webhook). |
| `Database.name = "default"` синтетический | `metadata.py` | Намеренно. SPEC §3.6 даёт 3-сегментный FQN, упаковываем в 4-сегментный OM-овый. |
| Tags из rdmmesh не маппятся в Table.tags | `metadata.py` `yield_table` | V1+: подцепить через `OMetaTagAndClassification`. На пилоте отдаём `[]`. |
| `markAsDeleted` через source-config `markDeletedTables` | workflow.yaml | OM сам делает diff'ы по previous ingestion. Включать `markDeletedTables: true` в YAML. |
| Глубокая рекурсия в `STRUCT.children` — одна глубина | `_build_column_from_property` | Многоуровневые объекты на пилоте не встречаются. V1+. |
| `ARRAY` без element-type | `mapping.map_jsonschema_type` | JSON Schema `{"type":"array","items":{...}}` → `ARRAY` без `arrayDataType`. V1+. |
| Cache токена не thread-safe | `RdmmeshClient` | OM ingestion однопоточен. |
| Без жёсткой версии openmetadata-ingestion в pyproject | `pyproject.toml` | Намеренно: dev ставит OM через editable `pip install -e ../OpenMetadata/ingestion[base]`. Перед prod-release — pin'ить нужную stable-версию. |
| `service_spec.py` оставлен на случай fallback discovery | `service_spec.py` | OM 1.12 использует `sourcePythonClass` напрямую; `service_spec.py` подхватывается только если кто-то поменяет тип сервиса на свой custom enum. Сейчас не критичен. |

---

## 5. Указатели на следующие эпики

> Конкретное содержание — в SPEC §5.1.

### E13. Bitemporal & Hierarchy (следующий)

- **Где:** ядро rdmmesh (`rdmmesh-authoring`, `rdmmesh-distribution`,
  `rdmmesh-ui`); коннектор затрагивается **опционально**.
- **Что реализовать:**
  - Closure rebuild через триггеры/batch'и (handoff E4 §3 #1) для больших draft'ов.
  - Tree-редактор для Security/Access Matrix в UI (E11.3+).
  - Полный UI для `as_of`/`knowledge_as_of` параметров (distribution
    endpoints в E8 уже их принимают).
- **Возможное расширение коннектора:** при появлении closure-table в rdmmesh
  можно эмитить `Table.tableConstraints` с FOREIGN_KEY для иерархических
  справочников (отсылка на parent_key). На E12-MVP не реализовано.

### E14. Compliance hardening

- Криптографическая audit-цепочка — handoff E10 §3 #1.
- Унифицированное atomic-decision для split-tx случаев E5/E6/E7/E9/E10.
- Audit-export в S3 immutable bucket (SPEC §3.8 «V2»).
- **OM-side compliance:** Tags-классификация в OM (regulatory, IFRS9, etc.)
  с автоматическим маппингом из `rdmmesh.code_set.tags`. Это E14, не E13.

---

## 6. Smoke

### 6.1. Локально (то, что прошло на 2026-05-11)

В этой сессии прошло (Python 3.12.13 venv в `om-rdmmesh-source/.venv/`):
- Файловая структура `om-rdmmesh-source/` собрана корректно (namespace PEP 420) ✅
- `pip install -e ".[dev]"` ✅ (pydantic 2.13.4, requests 2.33.1, responses 0.26.0, pytest 8.4.2)
- `pytest -v` → **45/45 passed** in 0.18s ✅
  - 6 на `client.py`: auth caching, 500 Keycloak, 401-reauth, deserialize domains, latest_published sort, 5xx error
  - 12 на `mapping.py`: map_jsonschema_type (5), map_key_part_type (3), build_description (4)
  - 27 на `connection.py` парсер: `_options`/`_require`/`_parse_bool`/`_parse_int`/`_make_client`/`get_connection`
- `ruff check src/ tests/` → All checks passed ✅
- `python -m py_compile` на всех 6 модулях → exit 0 ✅
- Import smoke: `from metadata.ingestion.source.database.rdmmesh import client, connection, models, mapping` → OK ✅
- OM-репо **чистый**: `git status` показывает working tree clean ✅

В этой сессии **не прогонялось** (требует OM venv с openmetadata-ingestion):
- `import metadata.ingestion.source.database.rdmmesh.metadata` (импортирует OM SDK) ❌
- `metadata ingest -c rdmmesh-workflow.yaml` ❌
- e2e в OM UI ❌

### 6.2. End-to-end (следующая сессия)

См. §3.1–§3.4 для полного чек-листа. TL;DR:

1. Создать venv 3.10 в `OpenMetadata/ingestion/`, поставить `[base]`.
2. `pip install -e /home/daurena2609/projects/rdmmesh/om-rdmmesh-source[dev]`.
3. `pytest` внутри `om-rdmmesh-source/` — ждём 45/45 зелёных.
4. `make up` в `rdmmesh/` + поднять OM `docker compose up`.
5. Получить OM bot-jwt-токен.
6. Скопировать `examples/rdmmesh-workflow.yaml` → `*.local.yaml`, подставить
   `hostPort` + `clientSecret` + `jwtToken`.
7. `metadata ingest -c examples/rdmmesh-workflow.local.yaml` — ждём
   Source Status processed > 0.
8. В OM UI убедиться, что Service `rdmmesh` появился с правильной иерархией.

---

## 7. Открытые вопросы (актуальны для команды банка)

Без изменений с E11, плюс E12-specific:

1. Production-Strategy для Flyway — подтверждено: `autoMigrate=false` в prod.
2. Реальные prod-параметры Keycloak (issuer/jwks/audience/client_secret).
3. **OM API base URL и bot-токен** — теперь блокирует E12 prod-deploy. Нужны
   реальные значения от команды OM.
4. **OM Secret Manager** — как хранить `clientSecret` в OM-БД encrypted-at-rest.
   Варианты: Vault integration в OM, AWS Secrets Manager, KMS. Решение DevOps.
5. HMAC secret rotation policy — outbound (E6) / inbound (E7) / per-subscription (E9).
6. Уведомления (e-mail/Slack) approver'ам — V1+.
7. RDM_ADMIN substitution policy.
8. Имена env-vars для HMAC.
9. Webhook URL OM согласован с `/api/v1/webhooks/om/ownership`?
10. Политика «expert == steward».
11. APPROVER mapping.
12. Distribution — HTTP cache headers / rate-limit?
13. `/subscriptions` — domain-scoped RBAC?
14. Список зарегистрированных consumer'ов и их `secret_id`.
15. Audit retention policy implementation.
16. Audit-доступ (RDM_AUDITOR / RDM_ADMIN).
17. `actor=null` для OWNERSHIP_CHANGED.
18. AntD 5 vs 4.24.
19. UI host в проде.
20. CSP / HSTS для prod-UI.
21. **Кто публикует пакет `om-rdmmesh-source` для prod OM Airflow?** Варианты:
    (a) внутренний PyPI mirror банка, (b) `pip install` напрямую из git-репо
    rdmmesh при сборке Airflow-image. Согласовать с DevOps. На пилоте — (b).
22. **Cadence ingestion'а в OM Airflow?** SPEC §2.4 говорит «раз в час,
    конфигурируемо». На пилоте достаточно раз в сутки.

---

## 8. Версия документа

- **0.2** — 2026-05-11. Переключение на vanilla-OM подход
  (`CustomDatabaseConnection` + `sourcePythonClass`) после feedback'а от
  пользователя: «OM это стороннее решение, обновление OM не должно сильно
  затрагивать rdmmesh». Все правки в `openmetadata-spec` откачены. Добавлены
  27 unit'ов на `connection.py`-парсер (`connectionOptions: Map<String,String>`).
  Pytest: 45/45, ruff: clean. Автор: Claude Opus 4.7.
- **0.1** — 2026-05-11. Первоначальный вариант с собственным
  `RdmmeshConnection` в openmetadata-spec и патчем `databaseService.json`.
  Отозван — нарушал «vanilla-OM»-инвариант.
