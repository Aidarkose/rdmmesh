# ADR-0010 — Workflow topology-as-data (полный per-domain BPMN-маршрут)

> Статус: **Accepted — вариант B** (решение пользователя 2026-05-18,
> против рекомендации; принято и реализуется слайсами с обязательной
> compliance-сетью). История ниже сохранена как есть.
> Контекст: продолжение [`0009-flowable-bpmn-engine.md`](0009-flowable-bpmn-engine.md)
> (round 2 — модель A). BR-18, SPEC §3.2 #7 (no-bypass), §2.1/§3.8
> (4-eyes, self-approval), §2.3 (reproducibility).

## Проблема

Round 2 (модель A) даёт per-domain BPMN, но легальность переходов
авторитетно решает enum-`StateMachine` в `WorkflowService`. Значит
кастомная топология домена **ограничена формой 4-eyes**, разрешённой
enum-матрицей (DRAFT→IN_REVIEW→STEWARD_APPROVED→OWNER_APPROVED + reject).
Домен НЕ может задать принципиально иной маршрут (напр. две независимые
steward-ступени, параллельные approve, доп. стадия «Risk-review» с иной
ролью, hotfix-ветка без steward — BR-17). Это и есть «полный
topology-as-data».

## Почему это не «просто фича», а реверс решения

Снятие enum-`StateMachine` как авторитета легальности означает: **гарантия
no-bypass перестаёт быть свойством по построению** и становится зависимой
от (а) корректности нового топология-агностичного guard-сервиса и
(б) валидации каждого загруженного BPMN. Это compliance-критично
(SPEC §3.2 #7: даже RDM_ADMIN не обходит workflow; §3.8 self-approval).
Пользователь в round 2 осознанно выбрал модель A именно ради этой
гарантии. Поэтому — отдельный ADR и явное решение, не молчаливая правка.

## Опции

### B — параметризованная state-machine из BPMN-графа
`StateMachine` перестаёт быть hardcoded enum; легальные рёбра + требуемая
роль на ребро извлекаются из задеплоенного BPMN (граф = данные).
Guard-сервис (self-approval, role-gate, no-bypass) — топология-агностичен,
работает над graph-моделью.
- **+** максимальная гибкость (любой DAG ролей/стадий, BR-17 hotfix).
- **−** no-bypass теперь зависит от корректности guard-сервиса И парсера
  графа; самый большой риск/площадь атаки; нужен строгий property-based
  тест-сьют на инварианты.

### C — кастомная топология + deploy-time compliance-валидатор
BPMN задаёт маршрут, но `BpmnTemplateValidator` усиливается: обязательно
≥1 steward-approve и ≥1 owner-approve ступень, нет ребра «в обход» обеих,
нет self-edge actor=actor; runtime guard-сервис всё ещё проверяет
self-approval/role per-edge.
- **+** гибкая топология при сохранённых compliance-инвариантах,
  проверяемых статически + рантайм.
- **−** валидатор должен формально доказывать «нельзя достичь PUBLISHED
  без steward∧owner разными лицами» — нетривиально (reachability на
  графе); ошибка валидатора = дыра.

### A (текущее, baseline) — оставить как есть
enum-`StateMachine` авторитетна; per-domain BPMN кастомизирует только
оркестрацию в рамках 4-eyes-формы.
- **+** no-bypass по построению; нулевой compliance-риск; уже в проде.
- **−** нельзя расходящиеся маршруты / BR-17 hotfix через BPMN.

## Рекомендация

**Оставаться на A, пока бизнес явно не потребует расходящиеся per-domain
маршруты.** Если потребует — **C**, не B: статический compliance-
валидатор + рантайм-guard сохраняет аудируемую гарантию, тогда как B
переносит весь no-bypass в код без статической сети. B оправдан только
если нужны произвольные графы, которые C формально отвергает.

Триггер для перехода: конкретный бизнес-кейс (напр. домен Security/Access
Matrix требует доп. стадию, или BR-17 hotfix-workflow). Тогда — реализация
C отдельным эпиком с property-based тестами инвариантов и security-review
(как E14.8).

## Решение

**Принят вариант B** (пользователь, 2026-05-18 — осознанно против
рекомендации «A пока, затем C»). B реализуется **слайсами**, и
не-обсуждаемое условие: B НЕ поставляется без статической compliance-сети
+ property-тестов (иначе это дефект, не фича — ослабление no-bypass без
страховки недопустимо). Полный security-review кастомных графов перед
prod — gate (DoD §5.4), как E14.8.

### Слайс-план

- **B1 — сделано (E16.4):** топология как данные —
  `WorkflowGraph` (+`defaultFourEyes()` == прежняя enum-матрица 1:1),
  `StateMachine.validate` параметризован графом (`validate(req)` →
  `validate(req, defaultFourEyes())` — **нулевое изменение поведения**,
  StateMachineTest 22/22 зелёный). Compliance-сеть
  `WorkflowGraphInvariants`: доказывает, что любой граф, ведущий в
  `OWNER_APPROVED`, проходит STEWARD-, затем OWNER-approve-ребро (runtime
  guard'ы → 3 разных лица). Property-тесты: дефолт проходит, обходные
  графы (skip steward / fake-kind / submit-в-терминал / system-reject /
  недостижимость) отвергаются; self-approval на кастомном графе всё равно
  409.
- **B2 — сделано (E16.5):** per-domain BPMN несёт граф в
  process-extension `<rdm:workflowGraph>` (JSON-рёбра); `WorkflowGraphCodec`
  парсит, `BpmnTemplateValidator` гонит `WorkflowGraphInvariants` как
  **deploy-time gate** (невалид → 400, в Flowable/реестр НЕ попадает);
  канонический JSON хранится в `workflow_template.graph_json` (Flyway
  V033). `WorkflowService.resolveGraph(domainId)` судит каждый переход
  по графу домена версии (нет/битый → fail-safe дефолт + re-validate
  инвариантов на чтении — tampered DB-row не ослабит no-bypass).
  Доказано: IT — owner_reject легален в дефолте, но домен без него в
  графе → IllegalStateTransition; non-compliant граф → 400.
- **B3 — сделано (E16.6):** security-review attack-surface кастом-графа.
  Найдены и закрыты: **F-B1 (High)** — STEWARD-ребро без
  `recordReviewer` ⇒ steward==owner (4-eyes→2-eyes); **F-B4 (Medium)** —
  неограниченные SYSTEM-рёбра; **integrity** — OWNER→терминал без
  `setApprover`. `WorkflowGraphInvariants` ужесточён (инварианты 5/6 +
  setApprover), +4 adversarial-теста, нулевая регрессия (StateMachineTest
  22/22). Закрывает gate DoD §5.4 для B (ост. — E16.6 §4: прод-онбординг
  кастом-графов — операционное решение security/compliance банка).

**Итог:** вариант B (topology-as-data) функционально завершён и
compliance-safe (двойной рубеж deploy+runtime + ревью). Status —
**Accepted-B, closed**.

## Последствия (если примут C — на будущее)

- Новый `WorkflowGraph` (рёбра+роли из BPMN), guard-сервис над ним;
  `StateMachine` → адаптер графа дефолтного 4-eyes (обратная совместимость).
- `BpmnTemplateValidator` += reachability-проверка compliance-инвариантов.
- Security-review + property-тесты обязательны (gate, как DoD §5.4).
- Bitemporal-реестр шаблонов (V032) уже даёт reproducibility — достаточно.
