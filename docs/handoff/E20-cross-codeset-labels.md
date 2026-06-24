# E20 — Cross-CodeSet labels (Slice A)

## TL;DR

Display-only «связь по справочнику»: на оси матрицы (от `from_*` / `to_*`)
показываем не только код, но и «расшифровку» из другого CodeSet'а.

Пример: матрица просроченной задолженности с ключами `1..5`, словарь
`dpd_buckets` с items `1=0d`, `2=1-30d`, …, `5=closed`. UI рисует заголовок
оси как **`1 — 0d`**, а в DB по-прежнему лежит только `1`.

**Status:** Slice A — display-only, без валидации, без editor'а ref'ов.

## Что в этом слайсе

| Часть | Где | Что делает |
|------|-----|-----------|
| Schema | `rdmmesh-spec/schema/entity/key-spec.json` | `KeyPart.label_codeset_ref?: { codeset_id }` |
| TS types | `rdmmesh-ui/src/api/types.ts` | `LabelCodesetRef` + опциональное поле на `KeyPart` |
| UI form | `rdmmesh-ui/src/pages/DomainPage.tsx` | Select «Словарь меток для осей from/to» при выборе `transition_matrix` / `delinquency_matrix` preset'а |
| UI view | `rdmmesh-ui/src/components/RatingTransitionPivotView.tsx` | `useDictLabels()` хук + render «код — label» в строках/колонках |
| Wiring | `rdmmesh-ui/src/pages/VersionPage.tsx` | прокидывает `keySpec` в pivot view |

## Что НЕ в этом слайсе

- **Backend-валидация** ref'а: что `codeset_id` существует, что ключи матрицы
  есть в словаре. Сейчас `ref` хранится как метадата в JSONB-поле `key_spec`,
  Backend его не читает. Если словарь удалят — UI просто не покажет labels
  (graceful degradation).
- **CRUD-edit ref'а**: нельзя поменять словарь после создания CodeSet'а
  иначе как через прямой `PATCH` на key_spec (если такой API появится).
- **Cross-domain словари**: picker в form'е показывает только CodeSet'ы из
  того же домена. Если словарь в другом домене — придётся править key_spec
  через `Custom (JSON)`.
- **CSV/XLSX import по «человеческим» меткам**: bulk-импорт всё ещё ждёт
  «голые» коды (`1..5`), а не `0d/1-30d/…`. Резолв labels → код — отдельная
  работа Slice B (см. §«Дальше»).
- **Lookup по другим частям ключа**: ref сейчас всегда резолвится по
  `key_parts[0]` словаря. Multi-part lookup — Slice C.

## Контракт `label_codeset_ref`

```json
{
  "parts": [
    {
      "name": "from_bucket",
      "type": "STRING",
      "label_codeset_ref": { "codeset_id": "uuid-of-dpd_buckets" }
    },
    {
      "name": "to_bucket",
      "type": "STRING",
      "label_codeset_ref": { "codeset_id": "uuid-of-dpd_buckets" }
    },
    { "name": "period", "type": "ENUM", "allowed_values": ["1M","3M","6M","1Y"] }
  ]
}
```

- `codeset_id` обязателен; указывает на одноключевой CodeSet, где
  `key_parts[0]` — это «код», который надо подменить меткой.
- UI всегда смотрит на `parts[0].label_codeset_ref` (для DPD-кейса обе оси
  ссылаются на один словарь). Если `parts[1].label_codeset_ref` отличается —
  Slice A игнорирует второй ref. См. §«Дальше».

## Как UI резолвит labels

`useDictLabels(refCodesetId)` в `RatingTransitionPivotView`:

1. `api.getCodeSet(refCodesetId)` → получает `current_published_version` (строка `"0.1.0"`).
2. `api.listVersionsByCodeSet(refCodesetId)` → ищет UUID версии по строке + `status === "PUBLISHED"`.
3. `api.listItems(versionId, 0, 1000)` → берёт items.
4. Строит `Map<key_parts[0], label_ru || label_en>`.

React Query кэширует каждый шаг (`staleTime 30s`), переключение горизонта
матрицы НЕ дёргает сеть повторно. Если на любом шаге данных нет —
возвращается пустая мапа, заголовки рендерятся как «голые» коды.

## Smoke plan

1. Создать `dpd_buckets` в домене `credit_risk` (preset `single`):
   - `key_parts=["1"]`, `label_ru="0 дней (current)"`, `attributes={"days_min":0,"days_max":0}`
   - `key_parts=["2"]`, `label_ru="1–30 дней"`, …
   - …`key_parts=["5"]`, `label_ru="closed"`
   - 4-eyes flow → PUBLISHED.

2. Создать матрицу `delinquency_q1_2026` (preset `delinquency_matrix`):
   - В новом поле «Словарь меток» выбрать `dpd_buckets`.
   - Save.

3. Открыть draft матрицы → таб **Matrix view** → видим заголовки
   `1 — 0 дней (current)`, `2 — 1–30 дней`, …

4. Удалить published-версию словаря (или создать новый draft без publish) →
   заголовки деградируют к «1, 2, 3» без ошибок.

## Дальше (Slice B/C)

- **B1**: backend-валидация — при INSERT/UPDATE `code_item` matrix
  проверять, что `key_parts[0]`/`[1]` есть в текущей published-версии
  ref-словаря. Сильная: REJECT. Мягкая: WARNING.
- **B2**: «обратный» резолв при bulk-импорте — пользователь даёт CSV с
  колонкой `0d, 1-30d`, парсер ищет код в словаре. Полезно для
  бизнес-аналитиков, которые мыслят метками, а не кодами.
- **C1**: multi-part lookup — `label_codeset_ref` указывает не только
  `codeset_id`, но и `key_part_index` (если словарь композитный).
- **C2**: cross-domain словари — picker в form'е расширяется на все
  домены, к которым у пользователя есть read-grant.
- **C3**: editor для `key_spec` после создания CodeSet'а — сейчас
  `key_spec` immutable post-create, что не позволит «подключить» словарь к
  существующим матрицам без миграции данных.

## Открытые вопросы

- Должен ли ref продолжать работать, если у словаря только DRAFT-версия? Сейчас — нет (требуется PUBLISHED). Альтернатива: fallback на самую свежую DRAFT, если PUBLISHED нет. Решено отложить до feedback'а.
- Что если в словаре есть два item'а с одинаковым `key_parts[0]` (теоретически невозможно — unique constraint, но defensive)? Сейчас побеждает последний.
- Backend сохраняет `label_codeset_ref` в JSONB как есть. Если в будущем добавим валидацию (B1), нужна миграция для отсева мусора. Пока — none.
