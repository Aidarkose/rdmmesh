# om-rdmmesh-source

OpenMetadata ingestion source for **rdmmesh** — внешний коннектор, который читает REST API
сервиса `rdmmesh` и регистрирует справочники (CodeSet) в OpenMetadata как `Table`'ы
в синтетическом database service `rdmmesh`.

Реализует эпик **E12** из [`../SPEC.md`](../SPEC.md) §3.6. Документ-handoff —
[`../docs/handoff/E12-ingestion.md`](../docs/handoff/E12-ingestion.md).

## Архитектура

```
                      pip install -e .
om-rdmmesh-source ──────────────────────▶ OpenMetadata ingestion venv
                                                │
                                                │ namespace import:
                                                │ metadata.ingestion.source.database.rdmmesh
                                                ▼
                                          OM Workflow runner
                                          (стандартный `metadata ingest -c <yaml>`)
                                                │
                                                │ pull (cron в Airflow)
                                                ▼
                                     rdmmesh REST API (Bearer JWT
                                     через Keycloak client_credentials)
```

Pull-модель: коннектор сам ходит в rdmmesh (read-only) и пишет в OM. Никаких
изменений в rdmmesh не делается. Owner/expert/reviewer на CodeSets **не передаются**
ingestion'ом (SPEC §2.4): они назначаются в OM UI и текут обратно в rdmmesh через
E7-webhook.

## Маппинг

| rdmmesh                         | OpenMetadata                                  |
|---------------------------------|-----------------------------------------------|
| Domain (`/api/v1/domains`)      | DatabaseSchema (`rdmmesh.default.<domain>`)   |
| CodeSet                         | Table (`rdmmesh.default.<domain>.<codeset>`)  |
| CodeSetSchema property          | Column (data type из JSON Schema → OM type)   |
| CodeSet.description / tags      | Table.description / Table.tags                |
| Last PUBLISHED version (semver) | Table.version (string)                        |
| CodeSet.deleted_at IS NOT NULL  | `markAsDeleted=True` в OM                     |

> SPEC §3.6 даёт «3-сегментный FQN `rdmmesh.<domain>.<codeset>`». Реальный OM-FQN
> 4-сегментный (`service.database.schema.table`) — мы вставляем синтетический
> `database=default`. С точки зрения OM UI выглядит одинаково: пользователь
> навигирует service → default → domain → codeset.

## Установка для разработки

Pre-requisite: локальный clone OpenMetadata-ingestion (≥ 1.12) и его venv.

```bash
cd /home/daurena2609/projects/OpenMetadata/ingestion
python3.10 -m venv .venv
source .venv/bin/activate
pip install -e ".[base]"

# Подцепить наш коннектор
cd /home/daurena2609/projects/rdmmesh/om-rdmmesh-source
pip install -e ".[dev]"
```

После этого OM находит `metadata.ingestion.source.database.rdmmesh.service_spec.ServiceSpec`
по dotted-path discovery (без entry_points, см. `metadata/utils/service_spec/service_spec.py`).

## Конфигурация (workflow YAML)

```yaml
source:
  type: rdmmesh
  serviceName: rdmmesh
  serviceConnection:
    config:
      type: Rdmmesh                                       # enum value
      hostPort: http://localhost:8080                     # rdmmesh REST root
      keycloakIssuerUri: http://localhost:8090/realms/bank
      clientId: rdmmesh-backend
      clientSecret: dev-backend-secret                    # из Vault в prod
      requestTimeoutSeconds: 30
  sourceConfig:
    config:
      type: DatabaseMetadata
      includeTables: true
      includeViews: false

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

Запуск:

```bash
metadata ingest -c rdmmesh-workflow.yaml
```

## Тесты

```bash
pytest                          # unit с моками rdmmesh HTTP
pytest --cov=metadata.ingestion.source.database.rdmmesh
```

End-to-end smoke против реального OM — отдельная процедура, в handoff'е.

## Codegen Pydantic-моделей rdmmesh (опционально)

Wire-формат rdmmesh описан в `../rdmmesh-spec/schema/*.json`. Pydantic-модели
живут в `models.py` (handcrafted, узкий набор полей для коннектора). При
необходимости полной типобезопасности — генерация через:

```bash
pip install ".[codegen]"
python -m datamodel_code_generator \
  --input ../rdmmesh-spec/schema/entity \
  --input-file-type jsonschema \
  --output src/metadata/ingestion/source/database/rdmmesh/spec_models.py \
  --output-model-type pydantic_v2.BaseModel \
  --target-python-version 3.10
```

Решение использовать handcrafted vs codegen — см. handoff E12 §3.

## Связь с openmetadata-spec

Чтобы `type: Rdmmesh` распознавался в OM:

1. Создан файл `openmetadata-spec/.../database/rdmmeshConnection.json` (JSON Schema).
2. В `databaseService.json` добавлены:
   - `Rdmmesh` в enum `databaseServiceType`,
   - `{name: "Rdmmesh"}` в `javaEnums`,
   - `{"$ref": "./connections/database/rdmmeshConnection.json"}` в `oneOf`.

После этих правок OM нужно пересобрать (Pydantic-модели генерятся из JSON Schema).
Подробности — в `../docs/handoff/E12-ingestion.md` §1.

## Лицензия

Apache-2.0.
