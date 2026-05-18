# Примеры

## `rdmmesh-workflow.yaml`

Workflow для команды `metadata ingest -c rdmmesh-workflow.yaml`.

Использует **vanilla-OM** подход — generic `CustomDatabaseConnection` +
`sourcePythonClass` discovery. **Никаких правок в OM-репо не требуется.**

### Подготовка

1. **rdmmesh-стэк** поднят (`make up` в `../../`).
2. **OM Server** поднят на `workflowConfig.openMetadataServerConfig.hostPort`.
3. **OM ingestion venv** активирован, наш пакет установлен:
   ```bash
   cd /path/to/OpenMetadata/ingestion
   source .venv/bin/activate
   pip install -e /path/to/rdmmesh/om-rdmmesh-source
   ```
4. **OM bot JWT** получен — Settings → Bots → ingestion-bot → JWT.

### Подставить значения

Скопировать в `rdmmesh-workflow.local.yaml` (gitignored) и заменить:

| Поле | На что |
|---|---|
| `connectionOptions.clientSecret: dev-backend-secret` | реальный prod-secret (Vault) |
| `connectionOptions.hostPort` | URL rdmmesh REST в проде |
| `connectionOptions.keycloakIssuerUri` | prod Keycloak issuer |
| `connectionOptions.verifySSL: "false"` | `"true"` в проде |
| `workflowConfig...hostPort: http://localhost:8585/api` | prod-OM URL |
| `jwtToken: REPLACE_WITH_OM_BOT_JWT` | реальный OM bot JWT |

### Запуск

```bash
metadata ingest -c rdmmesh-workflow.local.yaml
```

Ожидаемый exit-status — 0; Source Status processed > 0.

### Что должно появиться в OM UI

Services → Databases → **`rdmmesh`** → `default` → `<имя-домена>` → `<имя-codeset>`

Каждая Table содержит:
- Колонки key-spec'а (`NOT_NULL`)
- Колонки атрибутов из CodeSetSchema (с правильным dataType, enum, NOT_NULL)
- Description с semver, hierarchy mode и key spec
- Owners / experts — **пусто** (назначаются вручную в OM, обратно к rdmmesh
  течёт через E7 webhook).

### Replay при изменениях контракта

Когда обновляется `connectionOptions` (новые поля, переименование) — обновить
парсер в `src/.../rdmmesh/connection.py` (`_make_client(...)`), потом
пересобрать пакет в OM venv (`pip install -e .` ещё раз). Сам OM не трогаем.
