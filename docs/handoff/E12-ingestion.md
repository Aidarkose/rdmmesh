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
> контракта и unit-тестов**. Pytest — **18/18 зелёные** (6 client с responses-mock'ами
> + 12 pure-mapping). Ruff — All checks passed. End-to-end smoke против
> реального OM Airflow **не прогонялся** — требует следующей сессии с
> поднятым OM-стэком (см. §6). Pip-пакет `om-rdmmesh-source` создан,
> namespace-import-discovery соответствует OM service-spec convention. В
> `OpenMetadata` (отдельный checkout) зарегистрирована схема подключения
> `RdmmeshConnection`.
>
> **Следующий эпик:** E13 (Bitemporal & Hierarchy). Указатели — в §5.

---

## 0. TL;DR за 30 секунд

- **Новый артефакт** — `/home/daurena2609/projects/rdmmesh/om-rdmmesh-source/`,
  pip-пакет (namespace package по PEP 420), 7 файлов кода + 3 тестовых файла.
  Реализует OM `DatabaseServiceSource` для rdmmesh REST API.
- **Маппинг**: rdmmesh Domain → OM `DatabaseSchema`, CodeSet → `Table` (FQN
  `rdmmesh.default.<domain>.<codeset>`), CodeSetSchema → `Column[]` (включая
  key parts с `Constraint.NOT_NULL`), last PUBLISHED semver → `Table.version`.
  Owners/experts/reviewers **не** переносятся (SPEC §2.4 — назначаются в OM,
  обратно текут через E7 webhook).
- **Auth**: Keycloak `client_credentials` (`rdmmesh-backend` client из E2),
  Bearer JWT с in-memory кэшем + автоматический re-auth при 401. Token TTL
  обрезан на 60s до истечения, чтобы не получить 401 в середине long-running
  ingestion'а.
- **Регистрация в OM**: в `openmetadata-spec` добавлен новый
  `rdmmeshConnection.json` + `databaseService.json` патчен в трёх местах
  (`enum`, `javaEnums`, `oneOf`). После этих правок нужно **пересобрать OM**
  чтобы регенерировались Java POJO и Python Pydantic — без regen'а коннектор
  не запустится (см. §3.1).
- **Discovery в OM**: dotted-path
  `metadata.ingestion.source.database.rdmmesh.service_spec.ServiceSpec` —
  не требует `entry_points` в `setup.py`. После `pip install -e
  om-rdmmesh-source/` в OM ingestion venv namespace-package резолвится
  автоматически.
- **Тесты**: 18 unit'ов (6 на `client.py` с responses-mock'ами Keycloak/rdmmesh +
  12 на pure-mapping helpers из `mapping.py`). Pure-маппинг вытеснен в отдельный
  модуль `mapping.py` без OM-импортов, поэтому **все 18 прогоняются в любом
  venv с requests/responses/pydantic** — OM SDK НЕ требуется. Прогнаны в
  Python 3.12.13: 18 passed in 0.17s.

---

## 1. Что сделано

### 1.1. Новые файлы — pip-пакет `om-rdmmesh-source`

```
rdmmesh/om-rdmmesh-source/
├── pyproject.toml            ← setuptools, find_namespace_packages, Pydantic 2, requests, pytest, responses, ruff, mypy
├── README.md                 ← инструкция установки в OM venv, формат workflow.yaml
├── .gitignore
├── examples/
│   ├── README.md             ← как готовить и запускать workflow
│   └── rdmmesh-workflow.yaml ← готовая заготовка для `metadata ingest -c`
├── src/metadata/ingestion/source/database/rdmmesh/
│   ├── __init__.py           ← пустой (concrete package, не namespace на этом уровне)
│   ├── models.py             ← Pydantic v2 wire-формат rdmmesh: Domain/CodeSet/Schema/Version
│   ├── client.py             ← RdmmeshClient: Keycloak client_credentials + REST endpoints
│   ├── connection.py         ← get_connection() с SHA-256-cache + test_connection() steps
│   ├── mapping.py            ← pure-helpers map_jsonschema_type / map_key_part_type / build_description (БЕЗ OM SDK)
│   ├── metadata.py           ← RdmmeshSource(DatabaseServiceSource) — основная логика (ИМПОРТИРУЕТ OM SDK)
│   └── service_spec.py       ← ServiceSpec = DefaultDatabaseSpec(metadata_source_class=RdmmeshSource)
└── tests/
    ├── __init__.py
    ├── test_client.py        ← 6 unit'ов на client (responses lib)
    └── test_mapping_pure.py  ← 12 unit'ов на pure-helpers из mapping.py
```

Структура каталогов под `src/metadata/ingestion/source/database/` намеренно
повторяет namespace OM — все папки до `rdmmesh/` это namespace-packages (без
`__init__.py`). Это позволяет OM найти наш модуль через обычный Python import
после `pip install`, без вмешательства в их `setup.py`.

### 1.2. Новые файлы — `OpenMetadata`

`/home/daurena2609/projects/OpenMetadata/`, ветка `main`:

| Файл | Что |
|---|---|
| `openmetadata-spec/src/main/resources/json/schema/entity/services/connections/database/rdmmeshConnection.json` | Новый — JSON Schema подключения. Поля: `hostPort`, `keycloakIssuerUri`, `clientId`, `clientSecret` (format=password), `requestTimeoutSeconds`, `verifySSL`, `schemaFilterPattern`, `tableFilterPattern`. `javaType=...RdmmeshConnection`, `enum=["Rdmmesh"]`. |

### 1.3. Изменённые файлы — `OpenMetadata`

| Файл | Что изменилось |
|---|---|
| `openmetadata-spec/.../entity/services/databaseService.json` | 3 точечных правки: (1) `Rdmmesh` добавлен в `enum databaseServiceType`; (2) `{"name":"Rdmmesh"}` в `javaEnums`; (3) `{"$ref":"./connections/database/rdmmeshConnection.json"}` в `oneOf` блока `databaseConnection.config`. Итого enum-count 57 → 58. |

### 1.4. Контракт rdmmesh REST → OM mapping

| rdmmesh                                    | OpenMetadata                                              |
|--------------------------------------------|-----------------------------------------------------------|
| `/api/v1/domains` → Domain                 | `DatabaseSchema(name=<domain.name>)` под synthetic `Database "default"` |
| Domain.displayName                         | DatabaseSchema.displayName                                |
| Domain.description                         | DatabaseSchema.description (Markdown)                     |
| `/api/v1/codesets/by-domain/{id}` → CodeSet| `Table(name=<codeset.name>, tableType=Regular)`           |
| CodeSet.displayName                        | (агрегируется в description; на E12-MVP отдельным полем не передаём — debt §4) |
| CodeSet.description + version + key_spec   | Table.description (Markdown с тремя секциями)             |
| `/api/v1/codesets/{id}/schema` → JSON Schema | Table.columns (см. §1.5)                                 |
| `/api/v1/versions/by-codeset/{id}` (PUBLISHED, latest) | Table.version (semver string, computed)         |
| CodeSet.deleted_at IS NOT NULL             | пропускаем в ingestion'е → OM сам делает `markAsDeleted` через `markDeletedTables` source config |
| CodeSet.tags                               | (не передаём на MVP — debt §4)                            |

#### FQN

SPEC §3.6 говорит про 3-сегментный FQN `rdmmesh.<domain>.<codeset>`. Реальный
OM-FQN всегда 4-сегментный (`service.database.schema.table`), поэтому
коннектор вставляет константный `database="default"`. Полный FQN получается
`rdmmesh.default.<domain>.<codeset>` (где `service="rdmmesh"` задаётся в
workflow.yaml через `serviceName`).

В OM UI пользователь видит навигацию: Service `rdmmesh` → Database `default` →
Schema `<domain>` → Table `<codeset>` — то же дерево, что и SPEC задумывает.

### 1.5. JSON Schema → OM Column

`_build_columns(schema_doc, codeset)`:

1. **Ключевые части** (`codeset.key_spec.parts`) — отдельные Column'ы в начале
   таблицы, всегда `Constraint.NOT_NULL`. Маппинг типов:
   - `STRING` → `STRING`
   - `INTEGER` → `BIGINT`
   - `NUMBER` → `DOUBLE`
   - `BOOLEAN` → `BOOLEAN`
   - `DATE` → `DATE`
   - `DATETIME` → `DATETIME`
   - `UUID` → `UUID`
   - default → `STRING`
2. **Атрибуты CodeItem** (`schema_doc.json_schema.properties`) — Column'ы после
   key parts. Маппинг JSON Schema → OM:
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
вложенных объектов не разворачиваются). Это V1+ если бизнес попросит. Для
пилотных IFRS9 справочников (плоская struct'ура) достаточно.

### 1.6. Auth flow в `RdmmeshClient`

```
RdmmeshSource.__init__
  ↓
get_connection(service_connection)              ← SHA-256 кэш по config
  ↓
RdmmeshClient.__init__                          ← lazy, без HTTP-call'ов
  ↓
[первый list_domains/list_codesets/...]
  ↓
_ensure_token():
  if не было токена OR < now: POST {issuer}/protocol/openid-connect/token
                              grant_type=client_credentials
                              client_id=rdmmesh-backend
                              client_secret=<encrypted in OM>
  cache token + expires_at = now + (expires_in - 60s)
  ↓
GET {host}/api/v1/...   Authorization: Bearer <token>
  if 401: forced re-auth, retry один раз
  if 5xx: raise RdmmeshApiError
```

Cache — in-memory на жизнь процесса OM Airflow worker'а (perfect for daily
ingestion). Token TTL обычно 300s в Keycloak dev — пересоздаётся каждый
запуск ingestion'а.

### 1.7. Discovery механизм

OM находит коннектор по dotted-path (см.
`metadata.utils.service_spec.service_spec.BaseSpec` docstring):

```
metadata.ingestion.source.database.{service_name}.service_spec.ServiceSpec
```

`service_name` берётся из enum value lower-cased: `Rdmmesh` → `rdmmesh`. То
есть OM делает `importlib.import_module("metadata.ingestion.source.database.rdmmesh.service_spec")`
и берёт оттуда `ServiceSpec`.

Это работает потому что:
- В `pyproject.toml` у нас `[tool.setuptools.packages.find]` + `namespaces=true`.
- Все промежуточные каталоги (`metadata/`, `metadata/ingestion/`, и т.д.) — БЕЗ
  `__init__.py` (PEP 420 implicit namespace packages).
- Только `metadata/ingestion/source/database/rdmmesh/__init__.py` присутствует
  (пустой) — это маркер concrete-package.

Поэтому в OM venv после `pip install -e om-rdmmesh-source` обнаружение
автоматическое — не нужно ни entry_points, ни патч `ingestion/setup.py`.

---

## 2. Контракт

### 2.1. Workflow YAML (input)

Пример из `om-rdmmesh-source/README.md`:

```yaml
source:
  type: rdmmesh
  serviceName: rdmmesh
  serviceConnection:
    config:
      type: Rdmmesh
      hostPort: http://localhost:8080
      keycloakIssuerUri: http://localhost:8090/realms/bank
      clientId: rdmmesh-backend
      clientSecret: dev-backend-secret
      requestTimeoutSeconds: 30
      verifySSL: true
      schemaFilterPattern:
        includes: [".*"]
      tableFilterPattern:
        includes: [".*"]
  sourceConfig:
    config:
      type: DatabaseMetadata
      includeTables: true
      includeViews: false
      markDeletedTables: true   # включить чтобы deleted_at CodeSet'ы помечались в OM
sink:
  type: metadata-rest
  config: {}
workflowConfig:
  openMetadataServerConfig:
    hostPort: http://localhost:8585/api
    authProvider: openmetadata
    securityConfig:
      jwtToken: <om-bot-token>
```

### 2.2. Errors

| Условие                                     | Exception / behaviour                                                |
|---------------------------------------------|----------------------------------------------------------------------|
| Keycloak token endpoint вернул не-2xx       | `RdmmeshAuthError` (fail-fast при первом запросе)                    |
| rdmmesh REST вернул 401                     | один раз re-auth и retry; если снова 401 → `RdmmeshApiError`         |
| rdmmesh REST вернул 4xx (кроме 401)/5xx     | `RdmmeshApiError` → останавливает ingestion (топология не толерантна) |
| `get_codeset_schema(...)` вернул 404        | yield `StackTraceError`, ingestion продолжается со следующим CodeSet |
| `CodeSet.deleted_at IS NOT NULL`            | пропускается на этапе `get_tables_name_and_type`                     |
| `Domain.deleted_at IS NOT NULL`             | пропускается на этапе `get_database_schema_names`                    |
| Тип connection не `Rdmmesh`                 | `InvalidSourceException` в `RdmmeshSource.create()`                  |

---

## 3. Что осталось доделать в E12 — обязательно перед smoke

### 3.1. CRITICAL — regen Pydantic / Java POJO в OpenMetadata

После правок в `databaseService.json` + нового `rdmmeshConnection.json` нужно
пересобрать OM, чтобы появились generated классы:

- `org.openmetadata.schema.services.connections.database.RdmmeshConnection` (Java)
- `metadata.generated.schema.entity.services.connections.database.rdmmeshConnection.RdmmeshConnection` (Python)

Без них:
- Java OM-service не примет `type: Rdmmesh` в workflow.yaml (валится при
  deserialization `DatabaseConnection`).
- Python `WorkflowSource.model_validate(config_dict)` упадёт на `oneOf`
  matching'е в `serviceConnection.config`.

Команды (внутри `/home/daurena2609/projects/OpenMetadata/`):

```bash
# Java: jsonschema2pojo по openmetadata-spec
./generate_ts.sh    # генерит TS типы; Java-codegen обычно идёт через mvn
mvn -pl openmetadata-spec generate-sources

# Python ingestion: datamodel-codegen
cd ingestion
make generate
```

(Точные команды могут отличаться в зависимости от Makefile в OM — см.
`/home/daurena2609/projects/OpenMetadata/ingestion/Makefile` target'ы `generate`
или `codegen`. Если make не работает — можно вызвать `datamodel-codegen` напрямую.)

### 3.2. CRITICAL — установить коннектор в OM ingestion venv

```bash
cd /home/daurena2609/projects/OpenMetadata/ingestion
python3.10 -m venv .venv && source .venv/bin/activate
pip install -e ".[base]"                          # OM SDK с CLI `metadata`

cd /home/daurena2609/projects/rdmmesh/om-rdmmesh-source
pip install -e ".[dev]"                           # наш коннектор + pytest

# Проверка discovery
python -c "from metadata.ingestion.source.database.rdmmesh.service_spec import ServiceSpec; print(ServiceSpec)"
```

Без этих двух шагов ничего не запустится.

### 3.3. Smoke (тот же чек-лист, что в handoff E11-* — не прогнан в этой сессии)

После выполнения §3.1 и §3.2:

```bash
# 1. Поднять полный backend (rdmmesh + keycloak + postgres)
cd /home/daurena2609/projects/rdmmesh
make up

# Создать данные для ingestion'а (E10 smoke даёт всё, что нужно):
# domain risk → codeset ifrs9_stages → DRAFT → 4-eyes → PUBLISHED 0.1.0

# 2. Поднять OM (можно через docker-compose из самого OM-репо)
cd /home/daurena2609/projects/OpenMetadata
docker compose -f docker/development/docker-compose.yml up -d
# или ./bin/openmetadata-server.sh start

# 3. Получить bot-токен OM (через UI или через POST /api/v1/users/auth-mechanism)

# 4. Запустить ingestion workflow
cd /home/daurena2609/projects/rdmmesh/om-rdmmesh-source
metadata ingest -c rdmmesh-workflow.yaml   # см. README

# Ожидаемый вывод: Source Status processed > 0 (Database + Schemas + Tables)

# 5. В OM UI: Services → Databases → rdmmesh → default → risk → ifrs9_stages
# Должна быть таблица с колонками key parts + stage (NOT NULL, ENUM ["1","2","3"]).
# В Description — semver и hierarchy_mode.
```

### 3.4. Прогнать unit-тесты

```bash
cd /home/daurena2609/projects/rdmmesh/om-rdmmesh-source
pytest                                  # 6 client-тестов (без OM SDK)
pytest --cov=metadata.ingestion.source.database.rdmmesh
```

`test_mapping_pure.py` (3 теста) выполнится только если OM SDK установлен —
иначе скипается `pytest.mark.skipif`.

### 3.5. Согласовать env-var для OM bot-токена

В workflow.yaml сейчас `clientSecret: dev-backend-secret` — это dev fallback,
тот же что в E2 realm-bank.json. Prod значение должно идти из Vault через
OM secret manager (см. open-question §7 п.3).

---

## 4. Технический долг и решения, повлиявшие на следующие эпики

| Что | Где | Когда снять / следующий шаг |
|---|---|---|
| Owners/experts/reviewers НЕ передаются ingestion'ом | `metadata.py` | **Не снимать** — это правильное поведение по SPEC §2.4 (OM master; обратно через E7 webhook). |
| `Database.name = "default"` синтетический | `metadata.py` | Намеренно. SPEC §3.6 даёт 3-сегментный FQN, который мы упаковываем в OM-овый 4-сегментный. Если бизнес попросит уйти от synthetic — придётся менять storage-layout (Domain как Database, CodeSet как Schema+Table?), что не вписывается в data-mesh-семантику. |
| Tags из rdmmesh не маппятся в Table.tags | `metadata.py` `yield_table` | V1+: подцепить через `OMetaTagAndClassification`, как в `database_service.py` шаблон. Тег создаётся в `yield_tag`. Сейчас отдаём `[]` и просто ставим версию в description. |
| `markAsDeleted` идёт через source-config `markDeletedTables` | workflow.yaml | OM сам делает diff'ы по previous ingestion и помечает удалённые. На пилоте включить `markDeletedTables: true` в YAML. Если нужно явное удаление сразу — расширить коннектор `mark_objects_as_deleted`. |
| Глубокая рекурсия в `STRUCT.children` — одна глубина | `_build_column_from_property` | Многоуровневые объекты в CodeSetSchema (массив объектов в объекте) на пилоте не встречаются. V1+ если IFRS9-домен попросит deep nested. |
| `ARRAY` без element-type | `_map_jsonschema_type` | JSON Schema `{"type":"array","items":{"type":"string"}}` сейчас даёт `ARRAY` без `arrayDataType`. Расширить — V1+. |
| Cache токена не thread-safe | `RdmmeshClient` | OM ingestion однопоточен — не нужно. Если когда-то понадобится — `threading.Lock` вокруг `_ensure_token`. |
| Locally pinning OM version — нет жёсткой версии в `pyproject.toml` | `pyproject.toml` | Намеренно: dev ставит OM SDK через editable `pip install -e ../OpenMetadata/ingestion[base]`. Перед prod-release — pin `openmetadata-ingestion >= 1.12, < 1.13`. |
| ~~Тесты `test_mapping_pure.py` скипаются без OM SDK~~ ✅ закрыто | `mapping.py` | Pure-helpers вытеснены в отдельный модуль без OM-импортов. Тесты прогоняются в любом venv. |
| RdmmeshConnection пока не в OM generated Pydantic | `connection.py` использует `Any` + `_config_to_dict` | После §3.1 regen'а заменить `Any` на typed import. Сейчас работает за счёт duck-typing. |
| FQN/imports `metadata.utils.fqn` приватный API OM | `metadata.py` `fqn.build(...)` + `fqn._build` (в burstiq) | OM stable API на 1.12.x; следить за изменениями в releases ≥ 1.13. |

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

- Криптографическая audit-цепочка (`prev_hash`/`entry_hash`) — handoff
  E10 §3 #1.
- Унифицированное atomic-decision для split-tx случаев E5/E6/E7/E9/E10.
- Audit-export в S3 immutable bucket (SPEC §3.8 «V2»).
- **OM-side compliance:** Tags-классификация в OM (regulatory, IFRS9, etc.)
  с автоматическим маппингом из `rdmmesh.code_set.tags`. Это E14, не E13 —
  компонент comlpiance hardening, не bitemporal.

---

## 6. Smoke

### 6.1. Локально (то, что прошло на 2026-05-11)

В этой сессии прошло (Python 3.12.13 venv в `om-rdmmesh-source/.venv/`):
- Валидация `rdmmeshConnection.json` и `databaseService.json` JSON parser'ом ✅
- Файловая структура `om-rdmmesh-source/` собрана корректно (namespace PEP 420) ✅
- `pip install -e ".[dev]"` ✅ (pydantic 2.13.4, requests 2.33.1, responses 0.26.0, pytest 8.4.2)
- `pytest -v` → **18/18 passed** in 0.17s ✅
  - 6 на `client.py`: auth caching, 500 Keycloak, 401-reauth, deserialize domains, latest_published sort, 5xx error
  - 12 на `mapping.py`: map_jsonschema_type (5), map_key_part_type (3), build_description (4)
- `ruff check src/ tests/` → All checks passed ✅
- `python -m py_compile` на всех 6 модулях → exit 0 ✅
- Import smoke: `from metadata.ingestion.source.database.rdmmesh import client, connection, models` → OK ✅

В этой сессии **не прогонялось** (требует OM venv с openmetadata-ingestion):
- `import metadata.ingestion.source.database.rdmmesh.metadata` (импортирует OM SDK) ❌
- `metadata ingest -c rdmmesh-workflow.yaml` ❌
- OM-side regen Pydantic / Java POJO ❌
- e2e в OM UI ❌

### 6.2. End-to-end (следующая сессия)

См. §3.1–§3.4 для полного чек-листа. TL;DR:

1. В `OpenMetadata/`: пересобрать `mvn -pl openmetadata-spec generate-sources` +
   `cd ingestion && make generate`.
2. Создать venv 3.10 в `OpenMetadata/ingestion/`, поставить `[base]`.
3. `pip install -e /home/daurena2609/projects/rdmmesh/om-rdmmesh-source[dev]`.
4. `pytest` внутри `om-rdmmesh-source/` — ждём 9/9 зелёных.
5. `make up` в `rdmmesh/` + поднять OM `docker compose up`.
6. Получить OM bot-jwt-токен.
7. `metadata ingest -c rdmmesh-workflow.yaml` — ждём Source Status processed > 0.
8. В OM UI убедиться, что Service `rdmmesh` появился с правильной иерархией.

---

## 7. Открытые вопросы (актуальны для команды банка)

Без изменений с E11, плюс E12-specific:

1. Production-Strategy для Flyway — подтверждено: `autoMigrate=false` в prod.
2. Реальные prod-параметры Keycloak (issuer/jwks/audience/client_secret).
3. **OM API base URL и bot-токен** — теперь блокирует E12 prod-deploy. Нужны
   реальные значения от команды OM.
4. HMAC secret rotation policy — outbound (E6) / inbound (E7) / per-subscription (E9).
5. Уведомления (e-mail/Slack) approver'ам — V1+.
6. RDM_ADMIN substitution policy.
7. Имена env-vars для HMAC.
8. Webhook URL OM согласован с `/api/v1/webhooks/om/ownership`?
9. Политика «expert == steward».
10. APPROVER mapping.
11. Distribution — HTTP cache headers / rate-limit?
12. `/subscriptions` — domain-scoped RBAC?
13. Список зарегистрированных consumer'ов и их `secret_id`.
14. Audit retention policy implementation.
15. Audit-доступ (RDM_AUDITOR / RDM_ADMIN).
16. `actor=null` для OWNERSHIP_CHANGED.
17. AntD 5 vs 4.24.
18. UI host в проде.
19. CSP / HSTS для prod-UI.
20. **Кто публикует пакет `om-rdmmesh-source` для prod OM Airflow?** Варианты:
    (a) внутренний PyPI mirror банка, (b) `pip install` напрямую из git-репо
    rdmmesh при сборке Airflow-image. Согласовать с DevOps. На пилоте — (b).
21. **Кто пересобирает OM с новой `rdmmeshConnection` schema?** Текущий
    подход — вмержить PR в наш fork OM-репо (`/home/daurena2609/projects/OpenMetadata`,
    ветка `main` в локальном fork'е). Альтернатива — поднять PR в upstream
    open-metadata/OpenMetadata. Решение бизнес-уровня — внешний коннектор
    вряд ли примут upstream, поэтому реалистично fork-стратегия.
22. **Cadence ingestion'а в OM Airflow?** SPEC §2.4 говорит «раз в час,
    конфигурируемо». Подтвердить с governance team — на пилоте достаточно
    раз в сутки, при росте количества справочников оптимизировать.
23. **Какую версию OM брать в prod?** В этой сессии локальный checkout —
    `1.12.0.0.dev0` (snapshot main-ветки от 2026-05-02), не released LTS.
    Перед prod — выбрать stable tag и pin'ить коннектор под него.

---

## 8. Версия документа

- **0.1** — 2026-05-11. Создан после реализации E12 (Ingestion-коннектор).
  Скелет pip-пакета + OM JSON Schema готовы; smoke (pytest + metadata ingest)
  не прогонялись из-за отсутствия Python 3.10 venv в этой сессии. Автор:
  Claude Opus 4.7.
