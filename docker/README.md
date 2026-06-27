# rdmmesh — docker dev stack

## Что запускается

- **postgres** — `postgres:16-alpine` с инит-скриптом `postgres/init/00-create-app-role.sql`, создающим runtime-роль `rdmmesh_app`. Схемы и таблицы создаст Flyway при первом запуске сервиса.
- **keycloak** — `quay.io/keycloak/keycloak:26.0` в режиме `start-dev --import-realm`. На boot импортирует realm `bank` из `keycloak/realms/realm-bank.json` (clients `rdmmesh-backend` и `rdmmesh-ui`; group'ы только `RDM_STEWARD`/`RDM_OWNER`/`RDM_VIEWER`/`RDM_ADMIN`; default-группа `RDM_VIEWER`). У каждого пользователя зафиксирован `id` (sub) и задан атрибут `objectGUID` → claim `oid` (dev-симуляция AD; в проде `oid` приходит из LDAP attribute mapper). Тестовые пользователи `dev-*` и `marat.suleimenov`/`aigerim.bekova`/… с паролем `dev`. Слушает 8090 на хосте.
- **rdmmesh-service** — multi-stage сборка по `Dockerfile`. Слушает 8080 (API) и 8081 (admin/healthcheck). Через env-vars знает про Keycloak issuer/JWKS и (опционально) про OpenMetadata REST.

## Команды

```bash
# Поднять стек:
make up   # или: docker compose -f docker/docker-compose.yml up -d

# Тыкнуть healthcheck:
curl http://localhost:8081/healthcheck

# Получить JWT для тестового пользователя:
make kc-token                                # dev-author/dev по умолчанию
KC_USER=dev-steward KC_PASS=dev make kc-token

# Подключиться к БД:
make psql                                    # rdmmesh_admin@rdmmesh

# Открыть Keycloak admin console:
make kc-admin                                # печатает URL'ы; admin/admin

# Остановить:
make down

# Полностью сбросить (миграции, данные, realm — всё):
docker compose -f docker/docker-compose.yml down -v
```

## URL'ы (dev)

| Что | URL |
|---|---|
| API | http://localhost:8080 |
| Admin / healthcheck | http://localhost:8081/healthcheck |
| Keycloak admin | http://localhost:8090/admin (admin/admin) |
| Realm `bank` | http://localhost:8090/realms/bank |
| OIDC discovery | http://localhost:8090/realms/bank/.well-known/openid-configuration |
| JWKS | http://localhost:8090/realms/bank/protocol/openid-connect/certs |
| Token endpoint | http://localhost:8090/realms/bank/protocol/openid-connect/token |

> ⚠ Из контейнера `rdmmesh-service` Keycloak адресуется как `http://keycloak:8080` (имя сервиса в docker network). Несовпадение `iss` (`http://keycloak:8080/realms/bank` против `http://localhost:8090/realms/bank` в браузере) решается флагом `KC_HOSTNAME_STRICT=false` и тем, что валидируем `iss` строго по тому, что прописано в `RDM_KC_ISSUER_URI`.

## Переопределения

Все значения по умолчанию подходят для локальной разработки. Чтобы что-то поменять — кладите `.env` рядом с `docker-compose.yml`. Поддерживаемые переменные:

| Переменная | Дефолт | Назначение |
|---|---|---|
| `RDM_DB_PORT` | `5432` | Порт Postgres на хосте |
| `RDM_DB_NAME` | `rdmmesh` | Имя БД |
| `RDM_DB_ADMIN_USER` | `rdmmesh_admin` | Суперпользователь БД (только для миграций / dev-операций) |
| `RDM_DB_ADMIN_PASSWORD` | `rdmmesh_admin_dev` | Пароль `rdmmesh_admin` |
| `RDM_DB_APP_PASSWORD` | `rdmmesh_dev` | Пароль `rdmmesh_app` (используется сервисом) |
| `RDM_HTTP_PORT` | `8080` | Порт API сервиса |
| `RDM_ADMIN_PORT` | `8081` | Порт admin/health |
| `KC_HTTP_PORT` | `8090` | Порт Keycloak на хосте |
| `KC_ADMIN_USER` | `admin` | Bootstrap-админ Keycloak |
| `KC_ADMIN_PASSWORD` | `admin` | Пароль bootstrap-админа |
| `RDM_KC_ISSUER_URI` | `http://keycloak:8080/realms/bank` | OIDC issuer, на котором сервис валидирует JWT |
| `RDM_KC_JWKS_URI` | `http://keycloak:8080/realms/bank/protocol/openid-connect/certs` | JWKS endpoint |
| `RDM_KC_AUDIENCE` | `rdmmesh-backend` | Ожидаемый `aud` claim |
| `RDM_OM_BASE_URL` | (пусто) | OpenMetadata REST base URL для lazy lookup `om_user_id` |
| `RDM_OM_BOT_TOKEN` | (пусто) | Bot-токен OM для lazy lookup'а |
