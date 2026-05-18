# om-rdmmesh-source

OpenMetadata ingestion source для **rdmmesh** — внешний коннектор, который читает
REST API сервиса `rdmmesh` и регистрирует справочники (CodeSet) в OpenMetadata
как `Table`'ы в database service `rdmmesh`.

Реализует эпик **E12** из [`../SPEC.md`](../SPEC.md) §3.6. Handoff —
[`../docs/handoff/E12-ingestion.md`](../docs/handoff/E12-ingestion.md).

## Принцип: vanilla-OM

**В OpenMetadata-репо никаких правок не делается.** Коннектор интегрируется через
готовые механизмы расширения, которые есть в OM из коробки:

- `CustomDatabaseConnection` (тип сервиса `CustomDatabase`) — generic connection
  type для любых third-party источников;
- `sourcePythonClass` — dotted-path к нашему `RdmmeshSource`, OM импортирует
  его через `metadata.utils.importer.import_from_module`;
- `connectionOptions: Map<String,String>` — свободный набор параметров
  (hostPort, keycloakIssuerUri, clientId, clientSecret и т.д.).

Это значит:
- Любой апгрейд OM не ломает коннектор (нечего вмердживать).
- Коннектор ставится в любой managed-OM Airflow одним `pip install`.
- Никаких pull-request'ов в open-metadata/OpenMetadata.

## Архитектура

```
                    pip install -e .
om-rdmmesh-source ──────────────────────▶ OM ingestion venv
                                                │
                                                │  metadata ingest -c rdmmesh-workflow.yaml
                                                ▼
                                          OM Workflow runner
                                                │
                                                │  CustomDatabaseConnection.sourcePythonClass
                                                │  → metadata.ingestion.source.database.rdmmesh.metadata.RdmmeshSource
                                                ▼
                                          RdmmeshSource (наш код)
                                                │
                                                │  Keycloak client_credentials → Bearer JWT
                                                ▼
                                     rdmmesh REST API (pull, read-only)
```

Pull-модель: коннектор сам ходит в rdmmesh и пишет в OM. Owner/expert/reviewer
**не передаются** ingestion'ом (SPEC §2.4 — назначаются в OM UI, обратно в
rdmmesh текут через E7 webhook).

## Маппинг

| rdmmesh                         | OpenMetadata                                  |
|---------------------------------|-----------------------------------------------|
| Domain (`/api/v1/domains`)      | DatabaseSchema (`rdmmesh.default.<domain>`)   |
| CodeSet                         | Table (`rdmmesh.default.<domain>.<codeset>`)  |
| CodeSetSchema property          | Column (data type из JSON Schema → OM type)   |
| CodeSet.description             | Table.description (с semver и hierarchy mode) |
| Last PUBLISHED version (semver) | Включается в Table.description                |
| CodeSet.deleted_at              | пропускается → OM `markDeletedTables`         |

> SPEC §3.6 даёт «3-сегментный FQN `rdmmesh.<domain>.<codeset>`». Реальный OM-FQN
> 4-сегментный (`service.database.schema.table`) — вставляем синтетический
> `database=default`. В OM UI пользователь видит то же дерево.

## Установка

Pre-requisite: локальный venv OM-ingestion с openmetadata-ingestion ≥ 1.12
(см. `/path/to/OpenMetadata/ingestion/`).

```bash
# 1. venv OM
cd /path/to/OpenMetadata/ingestion
python3.10 -m venv .venv
source .venv/bin/activate
pip install -e ".[base]"

# 2. Наш коннектор поверх
cd /path/to/rdmmesh/om-rdmmesh-source
pip install -e ".[dev]"
```

После этого OM находит `RdmmeshSource` через `sourcePythonClass` в workflow.yaml.

## Workflow YAML

См. [`examples/rdmmesh-workflow.yaml`](examples/rdmmesh-workflow.yaml) — готовая
заготовка. Ключевые части:

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
        verifySSL: "true"
```

Запуск:

```bash
metadata ingest -c rdmmesh-workflow.yaml
```

## connectionOptions

Все значения — строки (требование OM `ConnectionOptions = Map<String,String>`),
парсятся в `connection.py`:

| Ключ | Тип | По умолчанию | Описание |
|---|---|---|---|
| `hostPort` | str | — | URL rdmmesh REST root (обязательное) |
| `keycloakIssuerUri` | str | — | Issuer realm Keycloak (обязательное) |
| `clientId` | str | `rdmmesh-backend` | OIDC client_id |
| `clientSecret` | str | — | OIDC client_secret (обязательное) |
| `requestTimeoutSeconds` | int (как str) | — | таймаут на REST-запрос rdmmesh |
| `verifySSL` | bool (`"true"`/`"false"`) | `true` | проверка TLS-сертификата |

**Безопасность:** `clientSecret` в `connectionOptions` хранится в OM-БД как
обычная строка в map'е. Encrypt-at-rest зависит от того, как настроен сам OM
(SecretManager / KMS / Vault). Для prod — настраивать через OM Secret Manager
интеграцию; в dev — plain ОК.

## Тесты

```bash
pytest                                              # 45 unit'ов
pytest --cov=metadata.ingestion.source.database.rdmmesh
ruff check src/ tests/
```

Тесты НЕ требуют openmetadata-ingestion — только `requests`, `responses`,
`pydantic`, `pytest`. Покрытие:

- `test_client.py` (6) — Keycloak client_credentials + REST к rdmmesh.
- `test_mapping_pure.py` (12) — JSON Schema → OM dataType, key-part type,
  description builder.
- `test_connection_options.py` (27) — парсинг `connectionOptions`, кэш клиента,
  required-валидация, bool/int coercion.

End-to-end smoke против реального OM — отдельная процедура в handoff'е E12 §6.

## Codegen Pydantic-моделей rdmmesh (опционально)

Wire-формат rdmmesh описан в `../rdmmesh-spec/schema/*.json`. Pydantic-модели
живут в `models.py` (handcrafted, узкий набор полей для коннектора). Если
понадобится полная типобезопасность:

```bash
pip install ".[codegen]"
python -m datamodel_code_generator \
  --input ../rdmmesh-spec/schema/entity \
  --input-file-type jsonschema \
  --output src/metadata/ingestion/source/database/rdmmesh/spec_models.py \
  --output-model-type pydantic_v2.BaseModel \
  --target-python-version 3.10
```

## Лицензия

Apache-2.0.
