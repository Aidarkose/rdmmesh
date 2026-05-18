# rdmmesh-ui

React 18 + TypeScript 5 + AntD 5 + TanStack Table + react-i18next + Vite. Read-only
SPA для rdmmesh — каталог справочников, версии, items grid, history, My Tasks.

См. [`SPEC.md`](../SPEC.md) §3.1 (стек) и §5.1 (E11). Текущий handoff —
[`docs/handoff/E11-ui.md`](../docs/handoff/E11-ui.md).

## Быстрый старт (dev)

```bash
# 1. Поднять backend-стек
make up                   # postgres + keycloak + rdmmesh-service

# 2. Установить deps и стартовать UI
make ui-install           # npm install (один раз)
make ui                   # vite dev server на http://localhost:5173

# (опц.) перегенерировать TS-типы из rdmmesh-spec/schema/
make codegen-ts           # → rdmmesh-ui/src/generated/
```

После старта — открой http://localhost:5173, тебя редиректнет на Keycloak
(http://localhost:8090/realms/bank), залогинься как `dev-author`/`dev` (или
`dev-admin`/`dev-steward`/`dev-owner` — все с паролем `dev`, см.
`docker/keycloak/realms/realm-bank.json`).

## Конфигурация

Vite читает `import.meta.env.VITE_*`. Шаблон — `env.example`, скопируй в
`.env.local` и при необходимости переопредели.

| Переменная               | Дефолт                                  | Что делает                  |
| ------------------------ | --------------------------------------- | --------------------------- |
| `VITE_OIDC_AUTHORITY`    | `http://localhost:8090/realms/bank`     | OIDC issuer (Keycloak)      |
| `VITE_OIDC_CLIENT_ID`    | `rdmmesh-ui`                            | Public PKCE-клиент          |
| `VITE_API_BASE`          | `""` (тот же origin)                    | Префикс перед `/api/v1/...` |
| `VITE_DEV_API_TARGET`    | `http://localhost:8080`                 | Куда vite-proxy шлёт `/api` |

## Скрипты npm

```bash
npm run dev        # vite dev server (HMR, port 5173)
npm run build      # tsc -b && vite build (production)
npm run preview    # пред-смотр build'а на :4173
npm run typecheck  # tsc -b --pretty
npm run codegen    # TS-типы из rdmmesh-spec/schema/
```

## Структура

```
src/
├── auth/         OIDC PKCE (oidc-client-ts) + AuthContext + ProtectedRoute
├── api/          fetch-клиент, типы wire-формата, useApi hook
├── i18n/         react-i18next, ru.json + en.json
├── layout/       AppLayout (AntD Layout) + Lang/User menus
├── components/   Loader, StatusTag, ItemsTable
├── pages/        CatalogPage, DomainPage, CodeSetPage, VersionPage, MyTasksPage, ...
├── generated/    (gitignored) auto-generated TS из rdmmesh-spec/schema/
├── App.tsx       Routes
├── main.tsx      Entry: ConfigProvider + AuthProvider + BrowserRouter
└── config.ts     env-vars в типизированный объект
```

## Что есть и чего нет в Round 1

- **Есть:** OIDC PKCE login + token refresh, Catalog/Domain/CodeSet/Version/Items (read),
  pagination, My Tasks, transition history, verify-эндпоинт для published-версий, i18n RU/EN.
- **Нет (deferred → E11.2):** редактирование items (PATCH/bulk), создание DRAFT'а,
  workflow transitions UI, version diff side-by-side, Subscription management (E9 admin),
  Audit log viewer (V1+).
