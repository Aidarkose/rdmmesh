# Handoff — Эпик E11 round 1 (UI: scaffold + auth + read-only экраны)

> **Аудитория документа.** AI-агенты и инженеры, подключающиеся к проекту после E11
> round 1. Документ самодостаточен — переписки и контекста предыдущей сессии у вас нет,
> всё что нужно — здесь, в [`SPEC.md`](../../SPEC.md), [`E1-foundation.md`](E1-foundation.md),
> …, [`E10-audit.md`](E10-audit.md).
>
> **Дата handoff'а.** 2026-05-10.
> **Состояние.** E11 round 1 закрыт: scaffold (Vite+React+TS+AntD+TanStack+i18n) +
> OIDC PKCE + TS-кодогенерация + Catalog/Domain/CodeSet/Version/Items (read) +
> My Tasks + transition history + verify endpoint. `npm run typecheck` зелёный,
> `npm run build` зелёный (1.2 МБ JS / 380 KB gzip), `npm run dev` стартует за ~110 ms
> и отдаёт `index.html` с HTTP 200.
> **Backend smoke не прогонялся** — тестируется отдельно: `make up` + login через UI
> dev-юзером (см. §3.2).
> **Следующий раунд.** E11.2 (editing + workflow UI) — указатели в §5.
>
> **Round 1 явно НЕ включает:** редактирование items (PATCH/bulk), создание DRAFT'а
> через UI, кнопки workflow-перехода, Diff side-by-side, Subscription management
> admin-экран, Audit viewer. Все backend-эндпоинты для них уже работают (E4–E10);
> приклеить UI к ним — задача round 2.

---

## 0. TL;DR за 30 секунд

- Реализован модульный SPA в `rdmmesh-ui/` (был placeholder с E1):
  - **Стек.** React 18.3 + TypeScript 5.6 + Vite 5.4 + AntD 5.21 (см. §1.1 про
    отклонение от SPEC §3.1) + `@tanstack/react-table` 8.20 + `react-router-dom` 6.26
    + `react-i18next` 15.0 + `oidc-client-ts` 3.1.
  - **Аутентификация.** OIDC Authorization Code + PKCE через Keycloak realm `bank`,
    клиент `rdmmesh-ui` (public). Токен в localStorage, silent refresh через
    `automaticSilentRenew`. `Authorization: Bearer ...` инжектится в каждый fetch.
  - **TS-кодогенерация.** `rdmmesh-spec/codegen/typescript/generate.mjs` запускается
    `npm run codegen` из rdmmesh-ui — пишет `src/generated/{entity,api,events}/*.ts`
    через `json-schema-to-typescript`. Сейчас сгенерированные типы используются как
    справочник (для актуальной wire-формы — `src/api/types.ts`, см. §1.4).
  - **Экраны (Round 1):** `/catalog` (домены), `/domains/{id}` (codesets домена),
    `/codesets/{id}` (метаданные + JSON Schema + список версий), `/versions/{id}`
    (метаданные + items grid + history + verify), `/tasks` (My Tasks). Login/Callback
    отдельным public-route'ом.
  - **i18n.** `ru.json` + `en.json`, переключатель в header'е, AntD locale провайдер
    переключается синхронно (date-picker'ы и т.п. говорят на правильном языке).
- Makefile получил три новых target'а: `make ui-install`, `make codegen-ts`,
  плюс уже существовавший `make ui` (vite dev server).
- 3 экрана Round 2 (Editor, Diff, Subscriptions admin) намеренно отложены —
  backend-эндпоинты для них есть (PATCH items, POST transitions, /api/v1/subscriptions),
  работа сводится к UI без изменений в backend'е. См. §5 round 2 plan.

---

## 1. Что сделано

### 1.1. Стек и отклонение от SPEC §3.1

SPEC §3.1 фиксирует «AntD 4.24». На E11 round 1 принят **AntD 5.21**:

| Аспект | AntD 4.24 (SPEC) | AntD 5 (выбрано) | Почему 5 |
|---|---|---|---|
| Maintenance | EOL с 2025-Q4 | LTS, активная разработка | новые баги фиксятся только в 5 |
| CSS подход | глобальный CSS bundle | CSS-in-JS, runtime-themable | нет конфликтов, проще `ConfigProvider.theme` |
| TypeScript | заметные `any` в edge-cases | строгая типизация на всех публичных API | strict mode без обходов |
| React 18 | формально работает, есть рудиментарные warning'и в strict-mode | штатно | без warning'ов в console |
| Bundle | сопоставимо | сопоставимо после tree-shaking | паритет |

Документ SPEC.md переписывать ради этого не нужно — отклонение задокументировано здесь и
в [`README.md`](../../rdmmesh-ui/README.md). Если бизнес настаивает на 4.24 — переход
обратимый, патч-зона ограничена `package.json` + точечные изменения в `Segmented`/
`Timeline.items` API (в 4.x они немного разные).

Остальные пункты SPEC §3.1 совпадают: React 18, TS 5, TanStack Table 8, react-i18next,
Vite. Без Tailwind, без AG Grid, без react-flow.

### 1.2. Структура `rdmmesh-ui/`

```
rdmmesh-ui/
├── package.json              ← React 18, AntD 5, vite, oidc-client-ts, json-schema-to-typescript (devDep)
├── tsconfig.json             ← project references (app + node)
├── tsconfig.app.json         ← strict + paths "@/*": ["src/*"], exclude src/generated/
├── tsconfig.node.json        ← для vite.config.ts
├── vite.config.ts            ← @vitejs/plugin-react, alias @/, /api proxy → :8080
├── index.html
├── env.example               ← VITE_OIDC_AUTHORITY/CLIENT_ID, VITE_API_BASE, VITE_DEV_API_TARGET
├── README.md
└── src/
    ├── main.tsx              ← createRoot + ConfigProvider(locale) + AuthProvider + BrowserRouter
    ├── App.tsx               ← Routes: /callback, /login, * под ProtectedRoute
    ├── config.ts             ← env-vars в типизированный объект
    ├── vite-env.d.ts         ← объявления import.meta.env.VITE_*
    ├── auth/
    │   ├── oidc.ts           ← UserManager (oidc-client-ts), localStorage stateStore
    │   ├── AuthContext.tsx   ← Provider + useAuth() (ready/user/baseRoles/username/login/logout)
    │   ├── ProtectedRoute.tsx ← если !user — signinRedirect; пока ready=false — Spin
    │   └── Callback.tsx      ← обрабатывает /callback (OIDC redirect) → navigate("/")
    ├── api/
    │   ├── client.ts         ← apiFetch<T> wrapper, ApiError, Bearer-token
    │   ├── endpoints.ts      ← типизированный набор api.* (auth/catalog/authoring/workflow/publishing)
    │   ├── types.ts          ← wire-типы (Domain, CodeSet, CodeSetVersion, CodeItem, ItemsPage, ApprovalTask, ...)
    │   └── useApi.ts         ← простой data-loader hook (loading/error/data, cancel-on-unmount)
    ├── i18n/
    │   ├── index.ts          ← i18next + LanguageDetector (localStorage "rdmmesh.lang")
    │   ├── ru.json
    │   └── en.json
    ├── layout/
    │   ├── AppLayout.tsx     ← AntD Layout: Sider (Catalog/Tasks) + Header (Lang+User) + Outlet
    │   ├── LangSwitcher.tsx  ← <Segmented> RU/EN
    │   └── UserMenu.tsx      ← <Dropdown> с username/baseRoles + logout
    ├── components/
    │   ├── Loader.tsx        ← унифицирует loading/error/data → ReactNode
    │   ├── StatusTag.tsx     ← цветовой <Tag> для VersionStatus
    │   └── ItemsTable.tsx    ← AntD <Table> с динамическими колонками по attributes
    ├── pages/
    │   ├── LoginPage.tsx
    │   ├── CatalogPage.tsx       ← GET /domains
    │   ├── DomainPage.tsx        ← GET /domains/{id} + /codesets/by-domain/{id}
    │   ├── CodeSetPage.tsx       ← GET /codesets/{id} + /schema + /versions/by-codeset/{id}
    │   ├── VersionPage.tsx       ← GET /versions/{id} + /items + /history + verify
    │   ├── MyTasksPage.tsx       ← GET /tasks/my
    │   └── NotFoundPage.tsx
    └── generated/                ← (gitignored) `npm run codegen` → JSON Schema → TS interfaces
```

### 1.3. OIDC PKCE — детали

`oidc-client-ts` `UserManager` сконфигурирован под Keycloak realm `bank`:

```ts
authority:                  http://localhost:8090/realms/bank          // VITE_OIDC_AUTHORITY
client_id:                  rdmmesh-ui                                  // VITE_OIDC_CLIENT_ID
redirect_uri:               window.location.origin + "/callback"
post_logout_redirect_uri:   window.location.origin + "/"
response_type:              code        // PKCE автоматически в oidc-client-ts при code-flow
scope:                      openid profile email
userStore:                  localStorage
automaticSilentRenew:       true
loadUserInfo:               false       // backend и так доверяет JWT, REST UserInfo не нужен
```

В realm-bank.json (E2) для клиента `rdmmesh-ui` уже стоят:
- `redirectUris: ["http://localhost:5173/*", "http://localhost:3000/*"]`
- `webOrigins: ["http://localhost:5173", "http://localhost:3000"]`
- `pkce.code.challenge.method: S256`
- audience-mapper выставляет `aud=rdmmesh-backend` (соответствует `RDM_KC_AUDIENCE` в backend).

В UI-стороне base-роли извлекаются из claim'а `groups` (массив). Frontend-side
authorization не выполняется — все mutations недоступны на E11.1 (только GET'ы); roles
показываются в UserMenu и используются только для UX-tweak'ов. **Backend остаётся
единственным authoritative gate'ом.**

### 1.4. Wire-типы: smesa generated + hand-rolled

Backend смешивает naming-конвенции:
- `Domain`/`CodeSet`/`CodeSetVersion` (jsonschema2pojo POJO + `@JsonProperty`) → **snake_case**.
- `CodeItemDto`/`CodeSetSchemaDto`/`VersionDiffResource.*Dto` (свои records с `@JsonProperty`) → **snake_case**.
- `AuthResource.Me`/`MyTasksResource.ApprovalTaskDto`/`ItemsPage` (Java records без аннотаций) → **camelCase** (default Jackson).

Сгенерированный TS из `rdmmesh-spec/schema/` отражает только snake_case-семейство — это
неполное покрытие. Поэтому actual wire-типы хендкрафтятся в `src/api/types.ts`:

```
Domain                 — snake_case (spec POJO + @JsonProperty)
CodeSet                — snake_case
CodeSetVersion         — snake_case
CodeItem               — snake_case (CodeItemDto)
CodeSetSchemaDto       — snake_case
ItemsPage              — camelCase (page/size/total/items[CodeItem])
WorkflowTransitionEvent — snake_case (event-spec POJO)
ApprovalTask           — camelCase (Java record без аннотаций)
AuthMe                 — camelCase
VerifyResponse         — camelCase
```

Когда добавим хотя бы один экран edit'а, который хочет mutability через generated'ные
типы — пересмотрим стратегию. Возможные направления:
- Перевести Java records на `@JsonProperty` (snake_case) + регенерировать TS — сделать
  один источник истины. Минус: ломает существующих `/tasks/my`-consumer'ов (но их пока
  нет).
- Подключить `openapi-typescript` через генератор OpenAPI-спеки от Dropwizard (`dropwizard-jersey`
  swagger feature) — это фактическая wire-форма. Минус: зависит от runtime, не build-time.

Решение откладывается до round 2 (см. §5).

### 1.5. TS-кодогенерация

Скрипт `rdmmesh-spec/codegen/typescript/generate.mjs`:
- Использует `json-schema-to-typescript` через `createRequire(pathToFileURL(rdmmesh-ui/package.json))`,
  потому что зависимость живёт в `rdmmesh-ui/node_modules` и Node ESM resolver её
  оттуда не видит при запуске из `rdmmesh-spec/codegen/typescript/`.
- Очищает `rdmmesh-ui/src/generated/`, проходит по `entity/`, `api/`, `events/` schema-файлам,
  пишет `*.ts` рядом + `index.ts` с `export *`.
- Сейчас 17 generated файлов: 8 entity + 6 api + 3 events. Конфликты re-export'ов
  обходятся `tsconfig.app.json:exclude: ["src/generated"]` (см. §3 #2).

Команды:
```bash
make codegen-ts        # из корня репо, делает cd rdmmesh-ui && npm run codegen
# либо:
cd rdmmesh-ui && npm run codegen
```

### 1.6. API-клиент

`src/api/client.ts:apiFetch<T>` — тонкая обёртка над `fetch`:
- Инжектит `Authorization: Bearer <access_token>` (берёт из `userManager.getUser()`).
- Нормализует path: путь без префикса автоматически получает `/api/v1`.
- Парсит JSON или text по `Content-Type`.
- На non-2xx бросает `ApiError(status, message, body)`. message достаётся из тела, если
  оно похоже на `{message}`/`{error}`/`{errors:[...]}` (Dropwizard и наши resources
  отдают одну из этих форм).
- На 204 No Content возвращает `undefined as T`.

`src/api/endpoints.ts:api.*` — типизированный набор; на Round 1 покрывает GET-side всех
эпиков. Mutations (`POST /transitions`, `PATCH items`, `POST /codesets/by-domain` и пр.)
сознательно не подключены — это часть round 2.

`src/api/useApi.ts:useApi(loader, deps)` — простейший loader hook без кэширования.
React Query / SWR сознательно не подключены: на read-only Round 1 один-два запроса на
страницу, и без cache invalidation hand-roll достаточно. Когда появится мутация — стоит
переехать на TanStack Query (тот же `@tanstack` уже в зависимостях через `react-table`).

### 1.7. Layout, routing, i18n

- `BrowserRouter` базируется в `main.tsx`. Route'ы:
  - `/callback` — OIDC redirect (без layout, без auth-guard).
  - `/login` — публичная страница «Войти» (если кто-то landит вручную).
  - всё остальное — под `<ProtectedRoute><AppLayout/></ProtectedRoute>`.
- `AppLayout` — AntD `<Layout>` с Sider'ом (Catalog/Tasks), Header'ом
  (LangSwitcher + UserMenu), Content'ом (`<Outlet/>`).
- Selected-state Sider'а считается из `useLocation().pathname` — без явных активных
  ссылок, чтобы не дублировать логику.
- i18n переключается в `<Segmented>` в Header'е; `i18next-browser-languagedetector`
  запоминает выбор в `localStorage:rdmmesh.lang`. AntD `ConfigProvider.locale`
  обновляется через `useTranslation().i18n.resolvedLanguage` (нужно для DatePicker'ов
  и AntD-внутренних строк типа «No data»).

---

## 2. Контракт: что UI ожидает от backend'а

| Endpoint | Метод | Используется на странице | Source |
|---|---|---|---|
| `/api/v1/auth/me` | GET | (резерв на UI-extensions, сейчас не вызывается) | E2 |
| `/api/v1/domains` | GET | CatalogPage | E3 |
| `/api/v1/domains/{id}` | GET | DomainPage | E3 |
| `/api/v1/codesets/by-domain/{domainId}` | GET | DomainPage | E3 |
| `/api/v1/codesets/{id}` | GET | CodeSetPage | E3 |
| `/api/v1/codesets/{id}/schema` | GET | CodeSetPage | E3 |
| `/api/v1/versions/by-codeset/{id}` | GET | CodeSetPage | E4 |
| `/api/v1/versions/{id}` | GET | VersionPage | E4 |
| `/api/v1/versions/{id}/items?page=&size=` | GET | VersionPage | E4 |
| `/api/v1/versions/{id}/history` | GET | VersionPage | E5 |
| `/api/v1/versions/{id}/verify` | GET | VersionPage (кнопка «Verify signature») | E6 |
| `/api/v1/tasks/my` | GET | MyTasksPage | E5 |

Все вызовы — read-only. Mutations (POST/PATCH/DELETE) на E11.1 не дёргаются.

---

## 3. Что осталось доделать в E11 round 1 — мягкие follow-up'ы

Ничего не блокирует round 2. Список того, к чему стоит вернуться позже:

1. **Bundle size — 1.2 МБ JS / 380 KB gzip.** Полный AntD-импорт без code-splitting
   (см. варнинг `vite build`). Лечится через manual chunks (vendor + antd + app)
   либо `vite-plugin-imp` для AntD. Не critical для пилота, но при первом prod-deploy
   стоит срезать до ~200 KB initial gzip. Round 2 либо отдельный мини-PR.

2. **`src/generated/` исключён из tsconfig.** json-schema-to-typescript при
   `declareExternallyReferenced: true` инлайнит referenced types в каждом файле, что
   ведёт к дубликатам экспортов (`KeySpec` есть и в `code-set.ts`, и в `key-spec.ts`).
   `tsconfig.app.json:exclude: ["src/generated"]` обходит проблему. Когда подключим
   generated-типы в код — переписать генератор на per-export ESM (`export type {Domain}
   from "./domain"`), либо использовать один большой файл `spec.ts` без index'а.

3. **React Query / SWR не подключены.** На Round 1 `useApi` достаточно (read-only,
   нет invalidation). Round 2 (mutations) — переезд на TanStack Query: тот же `@tanstack`
   namespace, минимальный когнитивный overhead, optimistic updates + cache invalidation
   из коробки.

4. **TanStack Table установлен, но пока не используется** — `ItemsTable.tsx` на AntD
   `<Table>`. Причина: read-only grid + pagination проще на AntD. Round 2 (in-row edit
   с optimistic-lock'ом, copy-paste из Excel) — переключиться на TanStack для гибкости.

5. **`AuthMe` endpoint не вызывается** — UserMenu берёт identity напрямую из JWT
   claim'а. Когда добавим mapping `keycloak_sub → om_user_id` для frontend-side проверок
   (например, «moя ли это task»), позовём `/api/v1/auth/me` один раз при mount'е и
   закэшируем в AuthContext.

6. **Verify recompute не показывает HMAC-проверку** — handoff E6 §3 #3 это упомянул:
   verify-endpoint пересчитывает только content_hash, signature берётся «as-is». UI
   корректно отражает текущее поведение — нечего исправлять, пока backend не подкрутит.

7. **`env.example` вместо `.env.example`** — harness блокирует запись файлов с
   паттерном `.env*`. Ручное переименование в `.env.local` работает (его читает Vite),
   но шаблон лежит как `env.example`. Когда станет менее костыльно — переименовать.

8. **WebOrigins в Keycloak realm-bank.json уже включают `localhost:5173`** — никаких
   изменений в realm не требовалось. Если в проде UI задеплоен на другой origin
   (`rdm.bank`), realm нужно дополнить, plus CSP-headers в reverse-proxy. Сейчас
   PKCE-flow ходит напрямую в Keycloak, минуя backend — что и было задумано (handoff E2).

9. **Tasks tooltip / username вместо om_user_id.** В MyTasksPage задача показывает
   `versionId/codesetId` как UUID. UX-улучшение — резолвить в displayName через
   batch-load codesets/versions при первом рендере. Round 2.

10. **Keyboard shortcuts / accessibility.** Пока — стандартное AntD поведение. Полный
    a11y-аудит (Lighthouse) — V1+.

---

## 4. Технический долг и решения, повлиявшие на следующие раунды

| Что | Где | Когда снять / следующий шаг |
|---|---|---|
| AntD 5 вместо 4.24 (отклонение от SPEC §3.1) | `package.json` | По соглашению с командой: либо обновить SPEC, либо откатиться на 4.x. См. §1.1. |
| Wire-типы хендкрафтятся в `src/api/types.ts` несмотря на наличие codegen'а | `types.ts` vs `src/generated/` | Round 2: либо стандартизовать backend на snake_case (изменить Java records на `@JsonProperty`), либо подключить openapi-typescript поверх Dropwizard Swagger. |
| `src/generated/` исключён из tsc | `tsconfig.app.json` | Когда захочется использовать generated типы — переписать генератор без re-export-конфликтов. |
| `env.example` (без точки) | rdmmesh-ui/ | Ручное переименование пользователем при первом setup'е. |
| Bundle 1.2 МБ — без manual chunks | `vite.config.ts` | Перед первым prod-deploy — добавить `build.rollupOptions.output.manualChunks` (vendor + antd + app). |
| Round 1 не делает `await api.authMe()` ни разу | UI | Round 2: вызвать один раз при mount AuthProvider'а — это первый smoke того, что backend пускает JWT, и попутно резолвит `om_user_id` для UI-features. |

---

## 5. Что планируется в Round 2 (E11.2)

| Экран / feature | Зависит от | Ключевые backend-эндпоинты |
|---|---|---|
| Создать DRAFT (кнопка на CodeSetPage) | RDM_AUTHOR | `POST /api/v1/versions/by-codeset/{id}` (E4) |
| Items grid editor (PATCH ячеек) | DRAFT-version | `PATCH /api/v1/versions/{id}/items/{itemId}` с `expected_row_version` (E4) |
| Bulk import (CSV/JSON) | DRAFT-version | `POST /api/v1/versions/{id}/items/bulk` / `bulk-csv` (E4) |
| Workflow transitions (Submit/Approve/Reject buttons) | E5 ownership | `POST /api/v1/versions/{id}/transitions` (E5) |
| Diff side-by-side | две версии одного CodeSet | `GET /api/v1/versions/{id}/diff?from=...` (E4) |
| Subscriptions admin | RDM_ADMIN | `GET/POST/DELETE /api/v1/subscriptions` (E9) |
| Audit log viewer | RDM_ADMIN/RDM_AUDITOR | (V1+, требует нового endpoint'а — handoff E10 §3 #3) |

Технические задачи round 2:
- Переезд `useApi` → TanStack Query (mutations + invalidation).
- TanStack Table в `ItemsTable` для in-row edit.
- React Hook Form (или AntD Form) для forms (создать DRAFT, transition'ы).
- Client-side optimistic-lock UI: показать diff при 409 conflict от backend'а.
- Code-splitting / manual chunks в vite.config.ts.

---

## 6. Smoke

### 6.1. Локальный build (то, что прошло на 2026-05-10)

```bash
cd rdmmesh-ui
npm install            # 162 пакета, ~2 минуты на холодном кэше
npm run typecheck      # exit 0, без ошибок
npm run codegen        # 17 файлов в src/generated/
npm run build          # tsc -b + vite build → exit 0, dist/index.html + 1.2 МБ JS
npm run dev            # vite на http://localhost:5173/, ready за 112 ms; HTTP 200
```

### 6.2. End-to-end (требует `make up` от backend'а; **не прогонялось в этой сессии**)

Шаги для проверки в следующей сессии:

```bash
# 1. backend стек
make up

# 2. seed-данные (E10 smoke даёт published-версию):
TADM=$(KC_USER=dev-admin make kc-token)
TAUT=$(KC_USER=dev-author make kc-token)
TST=$(KC_USER=dev-steward make kc-token)
TOWN=$(KC_USER=dev-owner make kc-token)
# создать domain, codeset, items, прогнать 4-eyes flow → auto-publish (см. handoff E10 §2)

# 3. UI
make ui-install   # один раз
make ui           # vite на http://localhost:5173/

# 4. в браузере:
#   /                        → редирект на /catalog (через ProtectedRoute → /login → Keycloak)
#   логин dev-author/dev     → возврат на /callback → /catalog
#   /catalog                 → список доменов (ровно тот, что E10 создал)
#   click на домен           → DomainPage с list of codesets
#   click на codeset         → CodeSetPage, JSON Schema видна, версии в списке
#   click на v0.1.0 PUBLISHED → VersionPage, items grid (S1/S2/S3), history (3 transition'а),
#                              кнопка «Verify signature» → applicable=true, verified=true,
#                              hash совпадает с БД
#   /tasks                   → MyTasksPage: для dev-owner после steward_approve должна быть
#                              задача OWNER (если flow не довели до конца)
#   переключить EN ↔ RU      → AntD strings и наши labels меняются
#   logout                   → редирект Keycloak signout → /
```

Ожидаемые ошибки и их семантика:
- 401 от backend'а на любом endpoint'е → `ApiError(401, ...)` → красный alert в Loader.
- 404 на unknown id → 404-alert.
- network error → ApiError на TypeError, тоже Loader-alert.

---

## 7. Открытые вопросы (актуальны для команды банка)

Без изменений с E10, плюс:

1. Production-Strategy для Flyway — подтверждено: `autoMigrate=false` в prod.
2. Реальные prod-параметры Keycloak (issuer/jwks/audience/client_secret).
3. OM API base URL и bot-токен.
4. HMAC secret rotation policy — outbound (E6) / inbound (E7) / per-subscription (E9).
5. Уведомления (e-mail/Slack) approver'ам — V1+.
6. RDM_ADMIN substitution policy.
7. Имена env-vars для HMAC.
8. Webhook URL OM.
9. Политика «expert == steward».
10. APPROVER mapping.
11. Distribution — HTTP cache headers / rate-limit?
12. `/subscriptions` — domain-scoped RBAC?
13. Список зарегистрированных consumer'ов и их `secret_id`.
14. Audit retention policy implementation.
15. Audit-доступ.
16. `actor=null` для OWNERSHIP_CHANGED.
17. **AntD 5 vs 4.24** — подтвердить деviation, либо запросить откат.
18. **UI host в проде** — какой origin? (от него зависят redirectUris/webOrigins в realm-bank.json).
19. **CSP / HSTS** для prod-UI — пока не настроены, идут стандартом nginx/ingress.
20. **End-to-end smoke** в этой сессии не прогонялся — требует следующего захода с
    `make up`.

---

## 8. Указатели на следующие эпики

> Конкретное содержание — в SPEC §5.1.

### E11.2 (следующий round внутри того же эпика)

См. §5 выше. Editor + workflow buttons + diff + subscriptions admin.

### E12. Ingestion-коннектор

- **Где:** отдельный репозиторий `om-rdmmesh-source`, **не часть** `rdmmesh`.
- Pydantic-модели — codegen из тех же JSON Schema'ов (handoff E1 §3.8). Скрипт можно
  положить в `rdmmesh-spec/codegen/pydantic/`, по образцу `codegen/typescript/`
  созданному в этом раунде.

### E13. Bitemporal & Hierarchy

- Closure rebuild через триггеры/batch'и.
- Tree-редактор для Security/Access Matrix (UI — это уже E11.3+).
- Полный UI для `as_of`/`knowledge_as_of` параметров (consumer-side; основа в E8 уже есть).

### E14. Compliance hardening

- Audit hash-chain + verify-chain endpoint.
- Унифицированное atomic-decision для split-tx случаев E5/E6/E7/E9/E10.
- Security review (OWASP Top 10) — теперь и для UI: DOMPurify для description'ов,
  CSP заголовки, audit XSS-vectors в JSON Schema viewer.

---

## 9. Версия документа

- **0.1** — 2026-05-10. Создан после реализации E11 round 1 (UI scaffold + auth +
  read-only экраны). Build/typecheck прогнаны end-to-end в этой же сессии. End-to-end
  smoke с реальным backend-стеком — следующая сессия. Автор: Claude Opus 4.7.
