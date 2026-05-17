# Runbook — opt-in LIST-партиционирование authoring.code_item

> **Аудитория.** DBA / архитектор данных. Введено в **E14 round 7** как
> **opt-in шаблон** — `bootstrap/sql/optional/code_item_partitioning.sql`.
>
> **Статус на пилоте: НЕ применяется.** SPEC §3.4: партиционирование `code_item`
> отключено в MVP (объёмы малы). Шаблон лежит ВНЕ Flyway-пути
> (`bootstrap/sql/optional/` не сканируется), поэтому никогда не применяется
> автоматически.

---

## 1. Когда применять

Триггер — старые **DEPRECATED**-версии раздувают БД (SPEC §5.3 риск-таблица),
деградирует производительность, нужен дешёвый архив/DROP без `DELETE`
(а `DELETE` по published-иммутабельности и так нежелателен).

Не применять «на всякий случай»: партиционирование добавляет операционную
сложность (per-version партиции, обслуживание DEFAULT) без выигрыша на текущих
объёмах.

## 2. Модель

LIST по `version_id`. DEFAULT-партиция (`code_item_default`) поглощает все
строки — **приложение не меняется** («без рефакторинга кода», SPEC §3.4).
PK расширен до `(version_id, id)` (Postgres требует ключ партиции в PK);
`UNIQUE (version_id, key_parts)` уже совместим. FK на `code_set_version`,
индексы V020 и триггеры closure/cycle V022/V023 пересоздаются на
партиционированной таблице (ROW-триггеры каскадируются в партиции, PG13+).

## 3. Применение (maintenance-окно, под `rdmmesh_admin`)

```bash
# бэкап/PITR-точка обязательны
psql -U rdmmesh_admin -d rdmmesh -1 -f bootstrap/sql/optional/code_item_partitioning.sql
```

Идемпотентно (guard `relkind='p'`), одна tx (сбой → полный rollback). На млн
строк копия — минуты; closure-триггеры при копировании НЕ срабатывают (INSERT
идёт в новую таблицу до создания триггеров — closure-строки уже скопированы
отдельной таблицей `code_item_closure`, она не трогается).

> ⚠️ Проверка после: `SELECT relkind FROM pg_class WHERE
> oid='authoring.code_item'::regclass;` = `p`; spot-check `count(*)` до/после;
> прогнать smoke 4-eyes + diff + tree-editor.

## 4. Отщепление DEPRECATED-версии в свою партицию

```sql
-- после того как версия перешла в DEPRECATED:
SELECT authoring.ensure_code_item_partition('<version_id>');
-- строки этого version_id переносятся из DEFAULT в выделенную партицию
-- authoring.code_item_v<hex> — далее её можно архивировать и
-- DROP TABLE authoring.code_item_v<hex>; (вне retention DEPRECATED 10 лет!)
```

`ensure_code_item_partition` делает DETACH default → CREATE партиции →
перенос строк → ATTACH default. Разовая ops-операция в окне (берёт
ACCESS EXCLUSIVE на code_item на время DETACH/ATTACH).

## 5. Ограничения / откат

- Откат после успеха — только PITR/restore (структура пересоздана).
- DROP партиции версии — только за пределами retention DEPRECATED (10 лет,
  SPEC §3.7) и после архива. (Отдельной guarded-функции, как у audit, здесь
  нет — это ручная ops-операция; добавить guard при первом реальном применении.)
- Closure-таблица `code_item_closure` не партиционируется (отдельный объект,
  FK на `code_set_version`); при необходимости — отдельный шаблон.
