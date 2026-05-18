# Helm chart — rdmmesh

Деплоит JVM-сервис `rdmmesh-service`. Postgres и Keycloak — managed вне
чарта (SPEC §3.1 lean: 3 внешних компонента — Postgres, Keycloak, JVM).

```
helm upgrade --install rdm ./helm/rdmmesh -n rdmmesh \
  --set image.tag=0.1.0 \
  --set secret.existingSecret=rdmmesh-secrets \
  --set workflow.engine=flowable        # опц.: BPMN-движок (ADR-0009)
```

## Что входит

| Манифест | Назначение |
|---|---|
| `deployment.yaml` | сервис, probes на admin `/healthcheck`, env из ConfigMap + Secret |
| `service.yaml` | http (8080, ingress) + admin (8081, internal) |
| `configmap.yaml` | non-secret env (вкл. `RDM_WORKFLOW_ENGINE`, `RDM_ARCHIVE_LOCK_MODE`, `RDM_WEBHOOK_EGRESS_PRIVATE_ALLOWLIST`) |
| `ingress.yaml` | host + TLS + security-заголовки CSP/HSTS/… (E14.8 §3 #2) |
| `cronjob-audit-archive.yaml` | ежемесячная архивация (runbook `audit-archive.md`) |
| `cronjob-ensure-partition.yaml` | ежемесячная партиция (runbook `code-item-partitioning.md`) |

## Секреты

Чарт **не создаёт** Secret (SPEC §3.7 — Vault/SOPS). Оператор готовит
`secret.existingSecret` (Vault Agent / External Secrets / SOPS; в non-prod —
`kubectl create secret generic rdmmesh-secrets --from-literal=...`) с
ключами: `db-password`, `hmac-key`, `webhook-key`, `kc-client-secret`.

## Migrations (важно)

`config-prod.yml` ставит `flyway.autoMigrate=false` (prod-паритет,
E1 §3 / E2). Отдельной `migrate`-CLI-команды в сервисе **пока нет**
(E1 §3.5 — потребует `bootstrap.addCommand`). Варианты:

1. **Управляемый rollout (текущее):** `--set flyway.autoMigrate=true` на
   время апгрейда — миграции (вкл. `V031`/`V032`, создающие схему
   `workflow_engine` для Flowable) применяются при старте пода;
   `replicaCount` стартует последовательно (Flyway берёт advisory-lock,
   двойного применения нет).
2. **Целевое (follow-up):** добавить `migrate`-Command в сервис →
   pre-upgrade Helm hook Job. До этого — вариант 1 с осознанием.

Схема `workflow_engine` создаётся обычной Flyway-миграцией V031; ACT_*/
FLW_*-таблицы при `RDM_WORKFLOW_ENGINE=flowable` Flowable создаёт сам
(`databaseSchemaUpdate`, ADR-0009 carve-out) — отдельного шага не нужно.

## Статус / честная оговорка

Чарт **не** `helm lint`/`helm template`/deploy-протестирован в этой
сессии (нет k8s/helm/jq в среде; CI чарт не гоняет). Это валидные
Helm-шаблоны по спецификации, но первый реальный `helm template` →
`--dry-run` на стенде банка обязателен перед prod. CronJob'ы дают
**каркас** по runbook'ам (точная семантика verify/guarded-drop и имя
ensure-функции — в `docs/runbooks/`), `cronjobs.*.enabled=false` по
умолчанию.
