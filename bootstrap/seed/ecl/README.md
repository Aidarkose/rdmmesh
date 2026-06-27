# ECL / IFRS 9 — демо-справочники

Фикстуры для `make seed-ecl-references` (скрипт `scripts/seed-ecl-references.sh`).
Источник — схема `ecl__stg` проекта `ecl_dbt_project` (12 таблиц `r_ecl_*` /
`r_lnk_*` / `r_coef_*`), выгруженных в формат RDM CodeSet.

Каждый справочник = пара файлов:

| Файл | Что внутри |
|---|---|
| `<name>.codeset.json` | метаданные CodeSet: `key_spec` (части ключа + типы), `initial_schema` (JSON Schema атрибутов), labels, tags, `hierarchy_mode` |
| `<name>.items.json` | массив `NewItemRequest` для bulk-залива (`POST /versions/{id}/items/bulk`) |

## Состав (12 CodeSet'ов)

**Dimension (`kind:dimension`, ключ = `id`):**
- `r_ecl_branch_sgmnt` — сегменты филиалов (3)
- `r_ecl_prdct_sgmnt` — сегменты продуктов (6)
- `r_ecl_pledge_group` — группы залогов (10)
- `r_ecl_pledge_group_quality` — качество групп залогов (27)

**Mapping (`kind:mapping`, ключ = `id`, FK на dimension через атрибут `*_id`):**
- `r_lnk_branch_to_ecl_sgmnt` — филиал → сегмент (17)
- `r_lnk_prdct_to_ecl_sgmnt` — продукт → сегмент (90)
- `r_lnk_pledge_to_ecl_group` — залог → группа (51)

**Coefficient (`kind:coefficient`, составной ключ из месяца оценки и измерений):**
- `r_coef_pd` — PD, ключ `(estimation_mnth, product_id, branch_id, bucket, remaining_duration)` (12 222)
- `r_coef_pd_macro` — PD с макропоправкой (4 074)
- `r_coef_lgd` — LGD (1 210)
- `r_coef_ead_ttd` — EAD по TTD (42)
- `r_coef_indv_reserves` — индивидуальные резервы по сделкам (4 956)

## Замечания по дизайну

- **Bitemporal.** Исходные `valid_from`/`valid_to` (sentinel `9999-12-31` = «открыта»)
  загружены как обычные строковые атрибуты, а не как RDM-`effective_from/to`, чтобы
  залив гарантированно прошёл без edge-case'ов валидатора. При желании их можно
  перевести в нативную bitemporal-валидность RDM.
- **Даты в ключе** объявлены типом `DATE` (`estimation_mnth`, `report_dt`),
  числовые id — `INTEGER`. `load_dttm` — строковый атрибут (timestamp с пробелом, не RFC3339).
- **FK между справочниками** (например `branch_sgmnt_id`) оставлены простыми
  атрибутами, а не `parent_ref`, чтобы пример не зависел от настройки codeset-references.
- **Имя части ключа ≠ `id`.** Реляционное хранилище резервирует системные колонки
  `id` (uuid), `version_id`, `row_version`, `system_from`, `system_to`. Поэтому
  суррогатный ключ исходных таблиц назван `<entity>_id` / `lnk_id` / `quality_id`,
  а не `id` — иначе bulk-залив падает `cannot cast type uuid to bigint`.

## Два сценария загрузки этих фикстур

- **`make seed-ecl-references`** — автономный демо-домен `ecl_<sfx>` (без OpenMetadata),
  весь 4-eyes проходят dev-юзеры. Быстрый локальный пример.
- **`make seed-om-integration`** — OM-интеграция: домены **ECL** и **Airfly** заводятся
  с `om_domain_id` из OpenMetadata (`om-catalog`), создаются KC-учётки владельцев и
  стьюардов (DomainOwner→`RDM_OWNER`, expert→`RDM_STEWARD`), грузится role-directory,
  и эти же 12 справочников публикуются под настоящим доменом **ECL** реальным 4-eyes
  (автор `dev-author`, steward/owner — пользователи из OM). См. `scripts/seed-om-integration.sh`.

## Регенерация фикстур

`items.json` выгружаются из живой БД ECL (`ecl_postgres`, схема `ecl__stg`) через
`json_agg`/`json_build_object`. `codeset.json` ведутся вручную.
