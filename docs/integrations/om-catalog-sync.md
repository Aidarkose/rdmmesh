# OM → rdmmesh: синхронизация доменов и ролей (thin-notification + pull)

Интеграция (часть 1 event-driven контура): при изменении метаданных в OpenMetadata
нативный механизм **Alerts & Notifications** шлёт в rdmmesh тонкое уведомление через
webhook, а rdmmesh по нему сам обращается к **стандартному secure REST API OM** и
**выгружает только домены и роли**, апсертя их в зеркало.

```
OpenMetadata                                   rdmmesh-service
┌────────────────────────────┐                ┌─────────────────────────────┐
│ Alert                       │  thin POST     │ POST /api/v1/webhooks/om/    │
│ Domain_and_Roles_sync_      │ ─────────────▶ │      catalog-sync           │
│ for_RDMmesh (Notification)  │  (уведомление) │  (HMAC опц.; тело не доверяем)│
└────────────────────────────┘                │            │ resync()        │
            ▲                                  │            ▼                 │
            │ GET /api/v1/domains (Bearer bot) │  OpenMetadataCatalogClient   │
            │ GET /api/v1/roles   (Bearer bot) │  ── pull domains + roles ──▶ │
            └──────────────────────────────────┤  upsert catalog.domain       │
                  secure REST (system of record)│        catalog.om_role       │
                                                └─────────────────────────────┘
```

## Компоненты (rdmmesh)

| Слой | Где |
|------|-----|
| Webhook-приёмник (тонкий триггер) | `rdmmesh-ownership` → `resource/CatalogSyncWebhookResource` |
| Pull-сервис (полный идемпотентный ресинк) | `…/internal/om/CatalogSyncService` |
| OM REST-клиент (домены/роли) | `…/internal/om/OpenMetadataCatalogClient` |
| Зеркало доменов | `catalog.domain` (через `CatalogMirrorPort.upsertDomainFromOm`) |
| Зеркало ролей | `catalog.om_role` (миграция `V017`, `OmRoleDao`, `upsertRoleFromOm`) |
| Конфиг | `openmetadata.baseUrl` / `openmetadata.botToken` (`RDM_OM_BASE_URL` / `RDM_OM_BOT_TOKEN`) |

Стратегия pull — **полный ресинк** доменов и ролей на любое уведомление: просто,
идемпотентно (`op = CREATED/UPDATED/RESURRECTED/UNCHANGED`), без обработки delete-гонок.
Имя OM-домена нормализуется под CHECK `^[a-z][a-z0-9_]{0,63}$` (`Fin`→`fin`), оригинал
сохраняется в `display_name`.

## Настройка

```bash
OM_URL=http://localhost:8585 OM_ADMIN_PASSWORD=admin \
  bash scripts/om-catalog-sync-setup.sh
```
Скрипт логинится админом, генерирует долгоживущий PAT (bot-token), создаёт/обновляет
алерт и печатает env для `rdmmesh-service` (`RDM_OM_BASE_URL`, `RDM_OM_BOT_TOKEN`).
Затем перезапустите контейнер с этими переменными (и `RDM_OM_WEBHOOK_HMAC_KEY` для
inbound-подписи, если включаете её). `rdmmesh-service` и `om-server` должны быть в одной
docker-сети (`mesh-net`).

## HTTPS на pull-плече (rdmmesh → OM)

Pull `rdmmesh → OM` идёт по **HTTPS** (HTTP GET с bot-token наружу не разрешён). TLS
терминируется на `om-tls` (nginx, локальный аналог корпоративного ingress) перед
`om-server`; серт подписан внутренним CA. Конфигурация — в `rdmmesh/docker/docker-compose.yml`,
сервис `rdmmesh-service`:

- `RDM_OM_BASE_URL=https://om-tls:8443/` (в проде — `https://openmetadata.corp.<bank>/`);
- монтирование truststore: `./tls/om-ca-truststore.p12 → /opt/rdmmesh/tls/om-ca-truststore.p12`;
- `JAVA_OPTS += -Djavax.net.ssl.trustStore=/opt/rdmmesh/tls/om-ca-truststore.p12`
  `-Djavax.net.ssl.trustStorePassword=changeit -Djavax.net.ssl.trustStoreType=PKCS12`.

`om-ca-truststore.p12` = штатный `cacerts` JDK 21 + внутренний CA (`rdmmesh/docker/tls/`).
Клиент `OpenMetadataCatalogClient` (java.net.http) кода не меняет — TLS-handshake и проверку
цепочки делает JVM по этому truststore. TLS-терминатор поднимается из
`om-catalog/tls-gateway/`. Проверено: `OM: pull domains/roles` по `https://om-tls:8443`
без ошибок PKIX.

Вебхук `OM → rdmmesh` оставлен на HTTP (в уведомлении нет секретов, это внутренний
триггер); перевод его на HTTPS+HMAC — отдельная задача (формат подписи OM ≠ GitHub-style).

## Ограничение OpenMetadata 1.12.5

OM **не регистрирует `domain`/`role` как подписываемые ресурсы уведомлений** (валидны
`all`, `table`, `user`, `glossaryTerm`, …), и фильтра по типу сущности для `all` нет.
Поэтому алерт создаётся на `resources:["all"]`, а сужение **«только домены и роли»**
выполняется на стороне **pull** (`CatalogSyncService` тянет ровно `/domains` и `/roles`).
Изменения прочих сущностей лишь триггерят идемпотентный ресинк — данные в зеркало
попадают только доменов и ролей.

## HMAC-подпись уведомления

Приёмник проверяет подпись `X-OM-Event-Signature` / `X-OM-Signature` (`sha256=<hex>`)
ключом `RDM_OM_WEBHOOK_HMAC_KEY`, если она присутствует. Реальная граница безопасности —
сам pull по bot-token'у, поэтому при отсутствии подписи триггер выполняется (pull
идемпотентен). Формат подписи OM 1.12.5 отличается от GitHub-style верификатора rdmmesh,
поэтому в текущей конфигурации `secretKey` в алерте не задан (доставки идут без подписи);
согласование формата подписи — отдельная задача усиления.

## Проверено end-to-end

- Ручной триггер `POST /webhooks/om/catalog-sync` → `domains_synced=3, roles_synced=17`.
- Изменение описания домена `Fin` в OM → OM Alert автоматически доставил уведомление →
  rdmmesh выполнил pull (`sync domain op=UPDATED name=fin`) → новое описание в
  `catalog.domain`. Диагностика подписки OM: `successfulEventsCount`, `hasProcessedAllEvents=true`.
- В зеркале: домены `airfly/ecl/fin` (+ ранее `ecl_demo`), 17 ролей включая
  `DomainOwner`, `DomainDataSteward`, `DataSteward`.
