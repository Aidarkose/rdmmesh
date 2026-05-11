# Примеры

## `rdmmesh-workflow.yaml`

Workflow для команды `metadata ingest -c rdmmesh-workflow.yaml`.

### Подготовка

1. **rdmmesh-стэк** поднят (`make up` в `../../`).
2. **OpenMetadata пересобран** с правками `databaseService.json` +
   `rdmmeshConnection.json` (см. `../../docs/handoff/E12-ingestion.md` §3.1).
3. **OM Server** поднят и доступен по `workflowConfig.openMetadataServerConfig.hostPort`.
4. **OM ingestion venv** активирован, наш пакет установлен:
   ```bash
   cd /home/daurena2609/projects/OpenMetadata/ingestion
   source .venv/bin/activate
   pip install -e /home/daurena2609/projects/rdmmesh/om-rdmmesh-source
   ```
5. **OM bot JWT** получен — Settings → Bots → ingestion-bot → JWT.

### Подставить значения

Скопировать в `rdmmesh-workflow.local.yaml` и заменить:

| Поле | На что |
|---|---|
| `clientSecret: dev-backend-secret` | реальный prod-secret (Vault) |
| `jwtToken: REPLACE_WITH_OM_BOT_JWT` | реальный OM bot JWT |
| `hostPort: http://localhost:8080` | URL rdmmesh REST в проде |
| `keycloakIssuerUri: http://localhost:8090/realms/bank` | prod-Keycloak issuer |
| `hostPort: http://localhost:8585/api` (OM) | prod-OM URL |
| `verifySSL: false` | `true` в проде |

### Запуск

```bash
metadata ingest -c rdmmesh-workflow.local.yaml
```

Ожидаемый exit-status — 0. Source Status processed > 0 (Database + Schemas + Tables).

### Что должно появиться в OM UI

Services → Databases → **`rdmmesh`** → `default` → `<имя-домена>` → `<имя-codeset>`

Каждая Table содержит:
- Колонки key-spec'а (`NOT_NULL`)
- Колонки атрибутов из CodeSetSchema (с правильным dataType, enum, NOT_NULL)
- Description с semver, hierarchy mode и key spec
- Owners / experts — **пусто** (намеренно: назначаются вручную в OM,
  обратно к rdmmesh течёт через E7 webhook)
