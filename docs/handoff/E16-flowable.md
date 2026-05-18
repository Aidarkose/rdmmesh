# Handoff — Эпик E16 (V2 / BR-18: Flowable BPMN-движок, foundation-слайс)

> **Аудитория.** AI-агенты/инженеры после E16. Контекст —
> [`SPEC.md`](../../SPEC.md) §5.2 (V2), ADR-004 (§4.3),
> [`docs/adr/0009-flowable-bpmn-engine.md`](../adr/0009-flowable-bpmn-engine.md),
> [`E5-workflow.md`](E5-workflow.md) (§1.3 матрица, §1.4 split-tx),
> [`E14.14-r9-backlog-ci-gating.md`](E14.14-r9-backlog-ci-gating.md)
> (PostgresIT-singleton, CI-gating).
>
> **Дата.** 2026-05-18.
> **Состояние.** Закрыт foundation-слайс BR-18: in-process Flowable за
> тем же `WorkflowPort`, дефолтный 4-eyes как BPMN, guard'ы делегируются
> в существующий `WorkflowService` (без рефакторинга бизнес-логики).
> **CI зелёный end-to-end** (commit `f2c8b08`): `FlowableWorkflowIT`
> исполнился на ubuntu-dockerd — Tests run 1, **Skipped 0**; все 16 IT
> 0 fail/0 skip, BUILD SUCCESS. Локально IT Skipped (Docker-Desktop,
> E14.9 §2 — CI-авторитет); surefire/ArchUnit зелёные локально.

---

## 0. TL;DR

- **Seam `WorkflowEngine`** (workflow.internal.engine): `EnumWorkflowEngine`
  (дефолт, прямой `WorkflowService` — пилот 1:1) | `FlowableWorkflowEngine`
  (in-process BPMN). Флаг `RDM_WORKFLOW_ENGINE=enum|flowable` (дефолт
  `enum`, обратимо рестартом). `WorkflowTransitionResource` теперь зовёт
  `engine.transition(...)` (history — по-прежнему `service`).
- **BPMN `rdm4eyes`** (`resources/processes/rdm-4eyes.bpmn20.xml`):
  receive-task `rt_await` → service-task `${rdmTransitionDelegate}` →
  exclusive-gw (`terminal` → end | петля). `WorkflowTransitionDelegate`
  зовёт **существующий** `WorkflowService.transition` — вся валидация
  (self-approval/role-gate/no-bypass) и атомарные side-эффекты остаются
  в аудированном Java (ADR-004 «без рефакторинга»). Результат — через
  `TransitionResultHolder` (ThreadLocal; корректно — async выключен,
  `trigger` синхронный).
- **Lean сохранён** (SPEC §3.1): Flowable in-process на том же Postgres,
  свой JDBC-пул. ACT_*-таблицы Flowable создаёт сам
  (`databaseSchemaUpdate=true`) в изолированной схеме `workflow_engine`
  (Flyway **V031** создаёт схему+гранты, не структуру — carve-out,
  ADR-0009). История=`none`, async=off.
- **Fallback**: нет живого `rt_await` (post-terminal/системный
  publish/deprecate) → прямой `WorkflowService` (enum-легальность
  авторитетна и там; коды ошибок 1:1).

---

## 1. Файлы

| Файл | Изменение |
|---|---|
| `pom.xml` | `flowable.version=7.0.1` + depMgmt `flowable-engine`; convergence-пин `commons-io 2.18.0` (Flowable→liquibase) |
| `rdmmesh-workflow/pom.xml` | +`flowable-engine`, +`dropwizard-lifecycle` (Managed) |
| `bootstrap/sql/migrations/workflow/V031__workflow_engine_schema.sql` | **новый** — `CREATE SCHEMA workflow_engine` + грант USAGE/CREATE rdmmesh_app (carve-out) |
| `…/workflow/internal/engine/WorkflowEngine.java` | **новый** — seam |
| `…/engine/EnumWorkflowEngine.java` | **новый** — дефолт (→ WorkflowService) |
| `…/engine/FlowableWorkflowEngine.java` | **новый** — драйв процесса (lazy-start by businessKey, trigger, unwrap, fallback) |
| `…/engine/FlowableEngineManager.java` | **новый** — build ProcessEngine (standalone, свой пул, schema workflow_engine), deploy BPMN, `Managed` |
| `…/engine/WorkflowTransitionDelegate.java` | **новый** — JavaDelegate → `WorkflowService.transition` |
| `…/engine/TransitionResultHolder.java` | **новый** — ThreadLocal результат |
| `…/resources/processes/rdm-4eyes.bpmn20.xml` | **новый** — дефолтный 4-eyes |
| `…/workflow/WorkflowModule.java` | `EngineKind`, `FlowableDbConfig`, build-overload, `Resources.engineManager()` |
| `…/workflow/resource/WorkflowTransitionResource.java` | ctor `(WorkflowEngine, WorkflowService)`; `transition` → engine |
| `rdmmesh-app/.../RdmmeshConfiguration.java` | `WorkflowConfig{engine}` (default `enum`) |
| `rdmmesh-app/.../resources/config.yml` | блок `workflow.engine: ${RDM_WORKFLOW_ENGINE:-enum}` |
| `rdmmesh-app/.../RdmmeshApplication.java` | выбор движка; Flowable получает JDBC-координаты; `engineManager` → `environment.lifecycle().manage` |
| `rdmmesh-app/.../it/PostgresIT.java` | +`appJdbcUrl()/appUser()/appPassword()` |
| `…/it/FlowableWorkflowIT.java` | **новый** — Flowable гоняет полный 4-eyes + self-approval |
| `…/workflow/WorkflowModuleEngineSelectionTest.java` | **новый** — 2 unit'а выбора движка (без БД) |
| `docs/adr/0009-flowable-bpmn-engine.md` | **новый** — ADR |

ArchUnit чист: `workflow.internal.engine` — внутри workflow..,
resource→engine intra-module (паттерн E4 §1.10); IT-импорт
`workflow.internal.engine` — `ImportOption.DoNotIncludeTests` (прецедент
`AtomicRollbackIT`).

---

## 2. Контракт / поведение

- `RDM_WORKFLOW_ENGINE` не задан / `enum` → ровно прежний путь
  (нулевой риск, пилот не трогаем).
- `=flowable` → REST `POST /versions/{id}/transitions` идёт через
  BPMN-инстанс (businessKey=versionId, lazy-start на первом переходе),
  но решает легальность/пишет аудит тот же `WorkflowService`. Коды
  ошибок REST неизменны (409/403/404 — `FlowableWorkflowEngine.unwrap`
  достаёт оригинал из `FlowableException`).
- Per-domain BPMN-шаблоны в этом раунде **нет** (следующий) — топология
  `rdm4eyes` зеркалит enum-матрицу.

---

## 3. Проверка (2026-05-18)

```
./bin/mvn validate            → BUILD SUCCESS (convergence ок: 1 пин commons-io)
./bin/mvn -DskipITs verify    → BUILD SUCCESS (12 модулей, ArchUnit 11)
./bin/mvn verify              → BUILD SUCCESS
  surefire зелёный, +WorkflowModuleEngineSelectionTest 2, StateMachineTest 22
  failsafe: FlowableWorkflowIT обнаружен, локально Skipped (16 IT total),
    BUILD SUCCESS
```

> **Честная оговорка (как E14.14 §2 / E14.15 §3).** `FlowableWorkflowIT`
> локально **не исполняется** (Docker-Desktop ↛ Testcontainers через
> dockerized `bin/mvn`, E14.9 §2). Корректность — по чтению Flowable 7
> API (standalone cfg, `setBeans` на `ProcessEngineConfigurationImpl`,
> `RuntimeService.trigger`/`createExecutionQuery`), схем (V031 +
> WorkflowService side-эффекты) и логики. **Авторитетный прогон — CI**
> (ubuntu native dockerd, push-job `verify` без `-DskipITs`). Unit
> (выбор движка) и весь surefire — локально зелёные.

---

## 4. Что осталось / следующий раунд

1. ~~CI-прогон `FlowableWorkflowIT`~~ — **сделано** (`f2c8b08`,
   Tests run 1, Skipped 0). Потребовался CI-fix (см. §4a): первый push
   (`d7127b5`) упал — Flowable авто-активирует event-registry+IDM
   sub-движки, их Liquibase-changelog дублируется в shaded uber-jar
   → ProcessEngine не стартовал. Локально не воспроизводилось (IT
   Skipped без Docker — ровно задокументированный риск). Postgres-
   `databaseSchema` нюанс подтверждён рабочим (currentSchema в URL +
   setDatabaseSchema — ACT_* в `workflow_engine`).
2. **Per-domain BPMN (ядро BR-18)**: хранилище BPMN-определений per
   Domain + REST деплоя/версионирования + привязка к домену + выбор
   процесса по домену CodeSet'а. Тогда топология станет данными (сейчас
   `rdm4eyes` зеркалит enum).
3. **Очистка осиротевших инстансов** при `DELETE` DRAFT-версии
   (E5 — удаление версии не убивает Flowable-инстанс; корректности не
   ломает, но копит мусор в ACT_RU_*).
4. **Опц.**: единая tx (shared datasource Flowable↔app) если потребуется
   строгая атомарность инстанс↔состояние (сейчас — split, как E5 §1.4).
5. **Прочее без изменений:** Q56/Q62-финал, Helm (вкл. env
   `RDM_WORKFLOW_ENGINE` + проверка `workflow_engine`-схемы в prod-Flyway).

---

## 4a. CI-fix (commit f2c8b08) — НЕ откатывать

Первый push (`d7127b5`) дал зелёный локальный `verify` (IT Skipped),
но **красный CI**: `FlowableWorkflowIT` →
`FlowableException: Error initialising eventregistry data model` →
`liquibase … Found 2 files with the path
flowable-eventregistry-db-changelog.xml`. Причина: `flowable-engine`
транзитивно тянет event-registry + IDM sub-движки; они авто-активируются
и гоняют свои Liquibase-changelog'и, а в shaded uber-jar этот changelog
лежит по одному classpath-пути дважды (несколько flowable-jar'ов).

**Фикс:** `((ProcessEngineConfigurationImpl) cfg).setDisableIdmEngine(true)`
+ `.setDisableEventRegistry(true)` — нужен только core BPMN, sub-движки
не инициализируются (и их liquibase не парсится). Заодно lean (меньше
FLW_*/ACT_*-таблиц). **Инвариант:** не включать обратно IDM/EventRegistry
без решения проблемы дубликата changelog'а в shaded-jar (shade-relocation
/ exclude), иначе движок снова не стартует в prod-деплое.

## 5. Версия

- **0.1** — 2026-05-18. Foundation-слайс BR-18: seam `WorkflowEngine`
  (enum дефолт | Flowable), BPMN `rdm4eyes` делегирует в существующий
  `WorkflowService` (без рефакторинга, ADR-004/ADR-0009); Flowable
  in-process, self-managed DDL в `workflow_engine` (Flyway V031 carve-out,
  lean сохранён); config-флаг обратим. `./bin/mvn verify` BUILD SUCCESS,
  ArchUnit 11, `FlowableWorkflowIT` failsafe-discovered (CI-авторитет).
  Автор: Claude Opus 4.7.
