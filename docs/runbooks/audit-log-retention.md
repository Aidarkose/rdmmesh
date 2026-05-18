# Runbook — audit.audit_log: партиции, retention, hash-chain

> **Аудитория.** Оператор / DBA. Введено в **E14 round 7** (миграция V073).
>
> `audit.audit_log` — RANGE-партиционирована по `occurred_at` (помесячно) +
> `audit_log_default` (safety-net). Retention: 7 лет (audit) / 10 лет
> (DEPRECATED-связанные события) — SPEC §3.7.

---

## 1. Решение hash-chain × партиционирование (зафиксировано)

Цепочка (`prev_hash`/`entry_hash`, V072) — **глобальная**, по `id ASC` через
все партиции. Партиционирование её не трогает: V073 копирует строки байт-в-байт,
`verify-endpoint` (`GET /api/v1/audit/verify-chain`) использует
`findChainRange ... ORDER BY id` и корректно сшивает партиции.

**Инвариант:** партицию нельзя дропать, пока её верхняя граница внутри
retention-окна — иначе разорвётся верифицируемый сегмент. Дроп разрешён
**только** после immutable-архива сегмента (round 10, S3) и **только** за
пределами retention. Это форсится функцией
`audit.drop_audit_partition_if_archived(...)` — прямой `DROP TABLE` партиции
оператором в обход функции запрещён политикой.

## 2. Ежемесячное обслуживание (cron, под `rdmmesh_admin`)

Создавать партиции **заранее** (пока DEFAULT по будущему месяцу пуст):

```sql
SELECT audit.ensure_audit_partition(date_trunc('month', now())::date);
SELECT audit.ensure_audit_partition(
       (date_trunc('month', now()) + interval '1 month')::date);
```

Идемпотентно. Рекомендация: cron 1-го числа месяца + алерт, если
`audit_log_default` непуст:

```sql
SELECT count(*) FROM audit.audit_log_default;   -- ожидание: 0
```

> **Если DEFAULT непуст** (cron пропустил месяц): данные НЕ потеряны (safety-net
> сработал, chain цел). Remediation в maintenance-окне:
> `ALTER TABLE audit.audit_log DETACH PARTITION audit.audit_log_default;`
> создать недостающую помесячную партицию, перенести строки
> (`WITH m AS (DELETE FROM audit.audit_log_default WHERE occurred_at >= … AND < … RETURNING *) INSERT INTO audit.audit_log SELECT * FROM m;`),
> `ATTACH ... DEFAULT;`.

## 3. Retention-дроп (за пределами 7/10 лет, после архива)

1. Заархивировать партицию в immutable RustFS/S3 (round 10) — через
   backend `POST /api/v1/audit/archive` + `verify`. Сериализация включает
   `prev_hash`/`entry_hash` (ndjson через `AuditExportWriter`), факт
   фиксируется в `audit.archive_manifest`. Полный поток —
   [`audit-archive.md`](audit-archive.md).
2. Дропнуть через **1-арг** guarded-функцию (V074, round 10): archived
   выводится из манифеста — honor-system устранён, оператор НЕ передаёт
   `true` руками:

```sql
SELECT audit.drop_audit_partition_if_archived('audit_log_y2016m01');
```

`assert_segment_archived` откажет, если сегмента нет в
`audit.archive_manifest`; делегирует в замороженную V073 3-арг функцию,
которая откажет (`insufficient_privilege`), если граница внутри
retention. Старый 3-арг вызов остаётся как низкоуровневый primitive, но
**штатно используется 1-арг** (не доверяет аргументу-флагу). После дропа: глобальный verify даст разрыв на стыке с
дропнутым сегментом — это **ожидаемо**; полная проверка делается по
архиву + живому хвосту.

## 4. Откат V073

В пределах одной tx миграции откат автоматический (сбой → rollback). После
успешного применения откат — только PITR / restore из бэкапа (таблица
пересоздана, старая структура не сохранена). Это разовая структурная миграция,
как V072; прогон в maintenance-окне на проде (для млн строк копия — минуты,
вписывается в RTO ≤ 1 ч, SPEC §3.7).

## 5. Чек-лист prod-применения

- [ ] Бэкап/PITR-точка перед миграцией.
- [ ] Maintenance-окно (append-only пауза не требуется — миграция в одной tx).
- [ ] После: `SELECT relkind FROM pg_class WHERE oid='audit.audit_log'::regclass;` = `p`.
- [ ] `verify-chain` зелёный на всём диапазоне (chain не повреждён копией).
- [ ] cron `ensure_audit_partition` (текущий+след. месяц) настроен.
- [ ] Алерт на непустой `audit_log_default`.
