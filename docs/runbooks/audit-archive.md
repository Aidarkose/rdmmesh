# Runbook — ежемесячная архивация audit-сегментов в immutable-store

> Связано: [`audit-log-retention.md`](audit-log-retention.md) (партиции,
> retention-дроп, hash-chain), handoff'ы E14.11 (archive), E14.13 (этот
> round: pin тега, Q62 lock-mode, этот runbook).

## 0. Зачем

`audit.audit_log` партиционирован помесячно (V073). Retention требует
дропа старых партиций (7/10 лет, SPEC §3.7), но дропать **нельзя**, пока
сегмент не сложен в immutable-store: `audit.drop_audit_partition_if_archived(text)`
(V074, 1-арг) **выводит факт архива из `audit.archive_manifest`**, а не
из honor-system-аргумента. Архивацию делает backend (`POST
/api/v1/audit/archive`) — он сериализует сегмент детерминированно,
считает SHA-256, кладёт в RustFS/S3 с Object-Lock и пишет манифест.

Этот runbook — **ежемесячный cron**, который архивирует **прошлый**
месяц (текущий ещё пополняется) и верифицирует результат. Дроп —
отдельно, только за пределами retention (см. retention-runbook §3).

## 1. Предусловия

- `RDM_ARCHIVE_ENDPOINT` сконфигурирован (иначе `POST /audit/archive`
  → `503`, ArchivePort disabled-by-default).
- Bucket создаётся backend'ом автоматически с `objectLock(true)`.
- `RDM_ARCHIVE_LOCK_MODE` — см. §4 (Q62).
- Service-account с базовой ролью `RDM_ADMIN` (AD-группа → groups-claim).
  Рекомендуется отдельный `rdmmesh-archiver` (не переиспользовать
  `rdmmesh-bot` ingestion'а — разделение обязанностей).

## 2. Токен (Keycloak client_credentials)

```bash
TOKEN=$(curl -s -X POST \
  "$KC/realms/bank/protocol/openid-connect/token" \
  -d grant_type=client_credentials \
  -d client_id=rdmmesh-archiver \
  -d client_secret="$ARCHIVER_SECRET" \
  -d scope=openid | jq -r .access_token)
```

(Сервис-аккаунт должен иметь `RDM_ADMIN` в `groups`. Прод-секрет — из
Vault, не в манифесте CronJob открытым текстом — `secretKeyRef`.)

## 3. Ежемесячный поток (2-е число, прошлый месяц)

```bash
Y=$(date -u -d 'last month' +%Y); M=$(date -u -d 'last month' +%-m)
SEG="audit_log_y${Y}m$(printf %02d "$M")"

# 1. Архивировать прошлый месяц (идемпотентно).
curl -fsS -X POST -H "Authorization: Bearer $TOKEN" \
  "$API/api/v1/audit/archive?year=$Y&month=$M"
#  → {"segmentLabel":"audit_log_yYYYYmMM","contentSha256":"…",
#     "retentionApplied":true,"manifestInserted":true|false}

# 2. Независимая verify (скачивает из RustFS, пересчитывает SHA-256).
curl -fsS -H "Authorization: Bearer $TOKEN" \
  "$API/api/v1/audit/archive/$SEG/verify"
#  → {"verified":true, "computedSha256"=="manifestSha256"}
```

**Идемпотентность.** Повторный архив того же месяца: манифест —
`ON CONFLICT (segment_label) DO NOTHING` (→ `manifestInserted:false`),
объект — уже под Object-Lock (повторный PUT отвергается store'ом, это
ожидаемо). Сегмент уже зафиксирован — это успех, не ошибка. CronJob
должен трактовать `manifestInserted:false` + `verify:true` как OK.

**Когда архивировать.** Только когда месяц закрыт (события больше не
пишутся в ту партицию). 2-е число UTC — запас на поздние записи 1-го.

## 4. Q62 — GOVERNANCE vs COMPLIANCE (`RDM_ARCHIVE_LOCK_MODE`)

| Режим | Семантика | Когда |
|---|---|---|
| `GOVERNANCE` (default) | retention можно снять/укоротить ролью с `s3:BypassGovernanceRetention` | штатно; ops может исправить ошибочный retain-until |
| `COMPLIANCE` | **необратимо** до `retain_until` даже для root; объект нельзя удалить/перезаписать | regulator-mandated (IFRS9 неизменяемость) — по решению compliance банка |

Решение Q62: **режим конфигурируем, дефолт GOVERNANCE** (operationally
safe — ошибочную архивацию можно откатить). Переключать на `COMPLIANCE`
осознанно: после этого **любой** объект, записанный в этом режиме,
заблокирован на `month_end + 10y` без возможности отмены. Сначала
проверить корректность пайплайна на GOVERNANCE, затем флипнуть env и
рестартнуть сервис; ранее записанные GOVERNANCE-объекты режим не меняют
(per-object на момент PUT).

`retention_applied=false` в манифесте = store не принял Object-Lock
(иная сборка RustFS / off). Тогда immutability — только на bucket-policy;
`assert_segment_archived` выдаёт WARNING (не блок). Алертить.

## 5. k8s CronJob (пример)

```yaml
apiVersion: batch/v1
kind: CronJob
metadata: { name: rdmmesh-audit-archive, namespace: rdmmesh }
spec:
  schedule: "17 3 2 * *"          # 2-е число, 03:17 UTC
  concurrencyPolicy: Forbid
  jobTemplate:
    spec:
      backoffLimit: 3
      template:
        spec:
          restartPolicy: Never
          containers:
            - name: archiver
              image: curlimages/curl:8.10.1   # + jq, либо busybox-обёртка
              envFrom: [{ secretRef: { name: rdmmesh-archiver-secret } }]
              command: ["/bin/sh","-c","/scripts/archive.sh"]
              volumeMounts: [{ name: s, mountPath: /scripts }]
          volumes:
            - name: s
              configMap: { name: rdmmesh-archive-script, defaultMode: 0755 }
```

`archive.sh` = §2 (токен) + §3 (archive + verify) с `set -euo pipefail`
и **non-zero exit при `verified:false`** (Job упадёт → алерт; дроп
 retention НЕ делать).

## 6. Failure-матрица

| Симптом | Причина | Действие |
|---|---|---|
| `503` на `POST /audit/archive` | `RDM_ARCHIVE_ENDPOINT` пуст | включить ArchivePort; до этого retention-дроп запрещён (нет манифеста) |
| `400` «audit_log пуст / сегмент пуст» | за месяц нет событий | OK (нечего архивировать); дроп пустой партиции допустим без манифеста? — НЕТ, `assert_segment_archived` всё равно требует строку. Для пустых месяцев — отдельное решение compliance (обычно их и не дропают) |
| `verify` `verified:false` | sha mismatch / объект повреждён/подменён | **инцидент**: НЕ дропать партицию; разбор (tamper? сбой store?) |
| `manifestInserted:false` | повторный архив (идемпотентно) | OK, если `verify:true` |
| `retention_applied:false` | store не принял Object-Lock | алерт; immutability только на bucket-policy |

## 7. Связь с retention-дропом

После архива+verify, и **только когда** верхняя граница партиции вне
retention-окна (7/10 лет), дроп — 1-арг функцией (V074, выводит archived
из манифеста; honor-system устранён):

```sql
SELECT audit.drop_audit_partition_if_archived('audit_log_y2016m01');
-- assert_segment_archived → манифест есть → делегирует в V073 3-арг
-- (retention-window/DEFAULT/not-found проверки не дублируются).
```

См. [`audit-log-retention.md`](audit-log-retention.md) §3.
