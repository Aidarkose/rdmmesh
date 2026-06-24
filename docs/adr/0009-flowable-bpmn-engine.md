# ADR-0009 — Flowable BPMN-движок за WorkflowPort (V2 / BR-18)

> Статус: **Accepted** (foundation-слайс), 2026-05-18.
> Контекст: SPEC §5.2 V2, ADR-004 (enum state machine, «подменяется
> адаптером без рефакторинга бизнес-логики»), BR-18 (custom BPMN per
> Domain). Развивает [`0001-modular-monolith-and-stack.md`](0001-modular-monolith-and-stack.md).

## Контекст

ADR-004 зафиксировал enum-`StateMachine` для MVP и явно предусмотрел
миграцию на Flowable «за `WorkflowPort`, без рефакторинга бизнес-логики»,
когда понадобятся кастомные BPMN-маршруты per Domain (BR-18). Этот ADR —
первый (foundation) шаг: интеграция движка и seam, без per-domain
шаблонов (следующий раунд).

Напряжения с инвариантами:
- SPEC §3.1 «Lean MVP, 3 внешних компонента» — Flowable не должен стать
  4-м.
- SPEC §3.2/§3.3 + E1 §1.3 — Flyway единственный DDL-авторитет, строгая
  принадлежность схем модулям.
- SPEC §3.8 / §3.2 #7 — 4-eyes-инварианты (self-approval, role-gate,
  no-bypass) и append-only audit не должны ослабнуть.

## Решение

1. **Seam — `WorkflowEngine`** (internal интерфейс workflow-модуля), две
   реализации: `EnumWorkflowEngine` (дефолт, прямой проход в
   `WorkflowService` — поведение пилота 1:1) и `FlowableWorkflowEngine`
   (in-process BPMN). Выбор — config-флаг `RDM_WORKFLOW_ENGINE`
   (`enum`|`flowable`), дефолт `enum`, обратимо рестартом.
2. **Бизнес-логика не дублируется.** BPMN-процесс `rdm4eyes`
   (receive-task → service-task → шлюз) на каждом шаге дергает
   `WorkflowTransitionDelegate`, который вызывает **существующий**
   `WorkflowService.transition` — вся валидация и атомарные side-эффекты
   (CAS+journal+approval-task+event) остаются там же. Flowable добавляет
   топологию/инстансы/историю и место под per-domain BPMN. Соответствует
   формулировке ADR-004.
3. **Lean сохранён.** Flowable работает in-process на **том же Postgres**
   — не новый внешний компонент. Собственный JDBC-пул Flowable (url/user/
   pass), без переплетения с Dropwizard-datasource.
4. **DDL-carve-out (осознанное, изолированное исключение из §3.2/E1
   §1.3).** ~25 ACT_*-таблиц Flowable создаёт и мигрирует сам
   (встроенный механизм, `databaseSchemaUpdate=true`) в **отдельной
   схеме `workflow_engine`**. Flyway-миграция V031 владеет только фактом
   существования схемы + грантами, не структурой таблиц движка (привязка
   к внутренней схеме Flowable была бы хрупкой на апгрейдах). Схема не в
   Flyway-`schemas`-списке; `flyway_schema_history` там не живёт.

## Альтернативы (отклонены)

- **Flowable DDL → Flyway-миграции.** Единый DDL-авторитет, но ручной
  re-vendor внутренней схемы Flowable на каждом апгрейде — хрупко.
- **Отдельная БД/datasource для Flowable.** Максимум изоляции, но +
  операционная поверхность — прямой конфликт с lean (SPEC §3.1).
- **Полный BR-18 сразу** (per-domain хранилище+REST+деплой) — больше
  риска; вынесено в следующий раунд.

## Round 2 — per-domain BPMN-шаблоны (2026-05-18)

Развитие ADR в рамках того же seam. Подтверждённые решения:

- **Guard-модель A.** Per-domain BPMN меняет оркестрацию, но легальность
  и guard'ы (self-approval / role-gate / no-bypass) остаются авторитетно
  в enum-`StateMachine` внутри `WorkflowService` (каждый переход всё
  равно проходит через делегат → `WorkflowService.transition`). Кастомная
  топология **не может** обойти 4-eyes по построению (SPEC §3.2 #7).
  Цена: «кастомность» ограничена формой, разрешённой enum-матрицей
  (полный topology-as-data — потенциальный отдельный ADR, если бизнес
  потребует расходящиеся маршруты).
- **Flowable native multi-tenancy.** Per-domain BPMN деплоится с
  `tenantId=domainId`; `FlowableWorkflowEngine` при старте инстанса
  выбирает tenant-процесс домена CodeSet'а, иначе дефолтный `rdm4eyes`
  (резолв домена best-effort — сбой → дефолт, переход не блокируется).
- **Контракт шаблона** (`BpmnTemplateValidator`, deploy-time → 400):
  BPMN обязан содержать receive-task `rt_await` и service-task
  `delegateExpression=${rdmTransitionDelegate}` (якоря движка). Глубокая
  compliance-валидация топологии не нужна — её роль выполняет модель A.
- **Реестр/аудит** `workflow.workflow_template` (Flyway V032,
  append-only-по-смыслу): кто/когда/sha256/версия активного шаблона
  домена — воспроизводимость (SPEC §2.3). Управление —
  `RDM_ADMIN`-only REST, регистрируется лишь при `engine=flowable`.
- **Долг round 3:** очистка осиротевших Flowable-инстансов при DELETE
  DRAFT; (опц.) полноценный topology-as-data, если потребуется.

## Последствия

- Положительные: реальный BPMN-движок за тем же `WorkflowPort`; субстрат
  под per-domain шаблоны; нулевой риск для пилота (дефолт = enum,
  обратимо); инварианты и аудит не тронуты.
- Цена: +зависимость `flowable-engine` 7 (convergence-пин `commons-io`
  2.18.0; тот же приём, что kotlin/commons-compress); второй DDL-
  авторитет в `workflow_engine` (изолирован, документирован);
  split-tx Flowable-инстанс vs authoritative-состояние (тот же класс,
  что E5 §1.4 — авторитет состояния в `authoring`/journal, CAS ловит
  конкуренцию).
- Долг следующего раунда: per-domain BPMN (хранилище+деплой+привязка к
  домену); очистка осиротевших инстансов при удалении DRAFT-версии;
  опционально — единая tx через shared datasource.
