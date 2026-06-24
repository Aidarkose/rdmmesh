# Handoff — Эпик E15 (Excel I/O: импорт/экспорт справочников)

> **Аудитория.** AI-агенты/инженеры, подключающиеся после E15. Контекст —
> [`SPEC.md`](../../SPEC.md) §2.2 этап 2, §3.5, [`E4-authoring.md`](E4-authoring.md)
> §1.8 (CSV-формат bulk-import), [`E8-distribution.md`](E8-distribution.md) §1.6
> (export csv/json, xlsx→501).
>
> **Дата.** 2026-05-17.
> **Состояние.** Новые фичи (вне исходных эпиков SPEC §5.1): **импорт справочников
> из Excel** и **экспорт в Excel** (csv/json уже были — E8). `./bin/mvn -DskipITs
> verify` BUILD SUCCESS; ArchUnit зелёный; unit'ы: +5 `XlsxBulkParserTest`,
> +2 `XlsxExporterTest`; `CsvBulkParserTest` (6) не задет рефактором.

---

## 0. TL;DR

- **Import (authoring):** `POST /api/v1/versions/{versionId}/items/bulk-xlsx`,
  `Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`,
  тело — бинарь .xlsx. Контракт колонок **идентичен CSV** (E4 §1.8): первая строка
  первого листа — заголовок. Роль `RDM_AUTHOR|RDM_ADMIN`, только в DRAFT, атомарно.
- **Export (distribution):** `GET /api/v1/rdm/{domain}/{codeset}/export?format=xlsx`
  теперь отдаёт .xlsx (был 501). Колонки — те же, что у csv-экспорта. `parquet`
  остался 501 (V1+); `csv`/`json` без изменений.
- **Библиотека:** dhatim **fastexcel** (writer) + **fastexcel-reader** —
  намеренно lean-альтернатива Apache POI (StAX/SAX-стриминг, ~3 транзитива против
  ~15 у POI). Согласуется с «Lean MVP» SPEC §3.1, не раздувает shade-jar.
- **DRY:** общий row-builder вынесен в `CsvBulkParser.buildRow(json, raw, idx)` —
  CSV и XLSX используют один разбор `key_parts`/`attr.*`/дат, ошибки идентичны.

---

## 1. Что сделано

### 1.1. Зависимости (`pom.xml`)

- Property `fastexcel.version=0.18.4`. dependencyManagement: `org.dhatim:fastexcel`,
  `org.dhatim:fastexcel-reader`.
- **Convergence-pin** (как kotlin-stdlib для minio): `commons-compress` пиннится на
  `1.27.1` — иначе enforcer `dependencyConvergence` ловит расхождение с версией из
  `testcontainers-bom` (reach: app→authoring→fastexcel-reader→commons-compress vs
  app→testcontainers→commons-compress). `opczip` пиннится на `1.2.0` (writer's
  declared) для детерминизма (единственное использование — формально не расходится).
- Транзитивы fastexcel 0.18.4 (проверено по POM на Maven Central): writer→`opczip
  1.2.0`; reader→`aalto-xml 1.3.3` + `commons-compress 1.27.1`. poi-ooxml у обоих —
  `test`-scope (к нам не приходит).
- `rdmmesh-authoring/pom.xml`: `fastexcel-reader` (compile) + `fastexcel` (test —
  собрать .xlsx-фикстуру). `rdmmesh-distribution/pom.xml`: `fastexcel` (compile) +
  `fastexcel-reader` (test — прочитать сгенерённый .xlsx).

### 1.2. Import

- `CsvBulkParser`: приватный `toRow` → `public static buildRow(ObjectMapper, Map<String,String>, int)`.
  Поведение CSV не изменилось (тот же `CsvBulkParserTest` зелёный).
- Новый `authoring.internal.xlsx.XlsxBulkParser` — fastexcel-reader, стримовое
  чтение первого листа; первая строка = header; значения берутся как
  **отображаемый текст** ячейки (`Row.getCellText`) — совпадает с тем, что
  пользователь видит в Excel (как CSV хранит набранное). Полностью пустые
  строки-заполнители пропускаются (не падают на отсутствии `key_parts`).
  Делегирует в `CsvBulkParser.buildRow`.
- `AuthoringService.bulkUpsertXlsx(versionId, InputStream, author)` — зеркало
  `bulkUpsertCsv`: parse → map в `NewItem` → `bulkUpsertJson` (атомарно; ошибка
  парсинга → `BulkResult.rejected` с `field="xlsx"`, ничего не записано).
- `CodeItemResource.bulkXlsx` — `@POST /bulk-xlsx`, `@Consumes(xlsx mime)`,
  `@RolesAllowed({"RDM_AUTHOR","RDM_ADMIN"})`. APPLIED→200, REJECTED→422 (как CSV).

### 1.3. Export

- Новый `distribution.internal.XlsxExporter.write(List<ItemDto>, OutputStream)` —
  fastexcel-writer, лист `items`, жирный header, колонки **те же, что CSV**:
  `key_parts, parent_key, label, description, attributes, order_index, status,
  effective_from, effective_to`. `key_parts`/`parent_key`/`attributes` — JSON-строка
  в одной ячейке (разворачивание по колонкам — тот же V1+ debt, что у CSV, E8 §4).
- `RdmDistributionResource.export`: ветка `xlsx` → `xlsxResponse` (StreamingOutput,
  `Content-Disposition: attachment`, имя `<domain>_<codeset>_v<ver>.xlsx`). `parquet`
  по-прежнему 501; сообщения об ошибках обновлены на `csv|json|xlsx`.

### 1.4. UI (rdmmesh-ui) — кнопки в интерфейсе

> Закрывает долг §4 «Нет UI-кнопок import/export». `npm run build`
> (`tsc -b && vite build`) зелёный.

- **Import:** в `BulkImportModal` добавлен третий таб **«Excel (XLSX)»**
  (рядом с JSON/CSV) — `Upload.Dragger` для бинарного `.xlsx` (без paste-textarea,
  файл не читается в текст). Виден на Version-странице DRAFT'а (роль
  `RDM_AUTHOR|RDM_ADMIN`). `apiMutations.bulkXlsx(versionId, File)` → POST
  `/items/bulk-xlsx` с бинарным body и точным MIME. Результат/ошибки —
  тот же `ResultPanel`, что у JSON/CSV.
- **Export:** в `ConsumerViewDrawer` (кнопка «Consumer view», видна для
  PUBLISHED/DEPRECATED) добавлен тулбар **XLSX / CSV / JSON** —
  `api.downloadDistributionExport(domain, codeset, format, {version,asOf,
  knowledgeAsOf,lang})` через `apiFetchRaw`→blob→synthetic `<a download>`
  (helper `triggerBlobDownload`, переиспользован и в audit-export).
  Формат скачивается по текущим resolve-фильтрам drawer'а.
- i18n-ключи добавлены в `ru.json`/`en.json` (`bulk.xlsx*`, `consumer.export*`).

---

## 2. Контракт (REST)

```
POST /api/v1/versions/{versionId}/items/bulk-xlsx
  Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet
  Body: бинарь .xlsx (1-й лист, 1-я строка — заголовок; колонки = CSV-контракт E4 §1.8)
  Auth: Bearer JWT, роль RDM_AUTHOR|RDM_ADMIN; версия в статусе DRAFT
  200 → BulkResult APPLIED {rowsAdded,rowsUpdated,rowsUnchanged}
  422 → BulkResult REJECTED (errors[].field="xlsx" при ошибке парсинга/валидации)
  409 → версия не DRAFT

GET /api/v1/rdm/{domain}/{codeset}/export?format=xlsx
  + те же query-params resolve версии (version/as_of/knowledge_as_of/lang)
  Auth: Bearer JWT
  200 → .xlsx attachment (лист "items", колонки = csv-экспорт)
  501 → parquet (V1+);  400 → неизвестный формат
```

---

## 3. Smoke (реально прогнан на 2026-05-17 против Docker-стека)

Скрипт `scripts/smoke-excel-io.sh` — round-trip без python-xlsx-либ
(openpyxl/xlsxwriter в среде нет): `seed-demo.sh` (4-eyes→PUBLISHED) →
**export xlsx** (distribution) → **import xlsx** в новый DRAFT (authoring) →
проверки + негативы. Прогон `make up` (rebuild image), результат:

```
==> EXPORT xlsx (distribution)      HTTP 200, 4042 байт, PK/zip-сигнатура ✓
==> IMPORT xlsx (authoring bulk-xlsx)
    {"status":"APPLIED","rowsTotal":3,"rowsAdded":0,"rowsUpdated":3,
     "rowsUnchanged":0,"errors":[]}
==> items в re-imported DRAFT       total=3 ✓
==> негатив: битый xlsx             422 REJECTED, errors[0].field="xlsx" ✓
==> негатив: format=parquet         501 ✓
SMOKE E15 OK
```

> `rowsUpdated=3` (не `rowsAdded`) — потому что новый DRAFT клонируется из
> published (E4 §1 «создать DRAFT из последней published»): 3 ключа уже есть,
> bulk-xlsx делает UPSERT. Это корректное поведение, не баг.

**Пере-прогон скрипта** больше не требует ручного `TRUNCATE
identity.rdm_user_mapping` — баг lazy-mapping исправлен (см. §5 #3).

---

## 4. Технический долг / решения для следующих эпиков

| Что | Где | Когда снять / шаг |
|---|---|---|
| XLSX-экспорт не разворачивает attributes по колонкам (одна JSON-ячейка) | `XlsxExporter` | V1+, синхронно с тем же debt CSV (E8 §4): resolve активной CodeSetSchema → колонки |
| `parent_ref` не задаётся через XLSX (как и CSV) | `AuthoringService.bulkUpsertXlsx` | при необходимости cross-codeset bulk — отдельная колонка/формат |
| `XlsxExporter` материализует `List<ItemDto>` в heap (наследует от `fetchAllItems`) | `DistributionService.fetchAllItems` | общий с csv/json стрим-debt E8 §4 — cursor-stream |
| `getCellText` берёт отображаемый текст: Excel-число `1` → "1" (ок для enum), но Excel-дата зависит от формата ячейки | `XlsxBulkParser.text` | для регуляторных дат рекомендовать текстовый формат колонок; документировать для пользователей |
| ~~Нет UI-кнопок import/export~~ — **закрыто**, см. §1.4 | `rdmmesh-ui` | XLSX-таб в BulkImportModal + export-тулбар в ConsumerViewDrawer |

---

## 5. Открытые вопросы

Без изменений с E14; плюс:

1. Максимальный размер загружаемого .xlsx (DoS-вектор: zip-bomb / много листов).
   Сейчас лимита нет — fastexcel-reader стримит, но стоит ввести `maxRequestSize`
   на endpoint'е (Jetty/Dropwizard) перед prod. Согласовать с эксплуатацией.
2. Нужен ли отдельный «шаблон .xlsx для скачивания» (header + пример) в UI (E11)?
3. ~~Обнаружен pre-existing баг вне scope Excel I/O~~ → **ИСПРАВЛЕН
   2026-05-18** (отдельно от E15, по запросу пользователя). Был: ленивый
   upsert `identity.rdm_user_mapping` конфликтовал по `keycloak_sub`; при
   новом `sub` с тем же `username` (пересборка Keycloak / сброс OM / 2-й
   логин) INSERT нарушал `unique(username)` → HTTP 500
   (`duplicate key rdm_user_mapping_username_key`) на первом запросе.
   Фикс: `UserMappingDao.upsert` → `ON CONFLICT (username) DO UPDATE SET
   om_user_id=EXCLUDED, keycloak_sub=EXCLUDED, …` — reconciliation по
   стабильному натуральному ключу `username` (SPEC §2.4 разрешает
   обновлять `om_user_id`; внешних FK на PK нет). Только SQL в DAO,
   миграция не нужна. Проверено live (stale-строка → `GET /domains` под
   dev-author → 200, строка реконсилилась). Workaround `TRUNCATE
   identity.rdm_user_mapping` больше не нужен.

---

## 6. Версия документа

- **0.1** — 2026-05-17. Создан после реализации Excel I/O. Автор: Claude Opus 4.7.
