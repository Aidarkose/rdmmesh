# Code review — E17 (адресная маршрутизация согласования) + проект

> **Аудитория.** Команда rdmmesh / AI-агенты. Самодостаточный документ:
> фиксирует результаты ревью после приземления E17 в `main`
> (commit `6b7f9ed`). Связанный контекст — [`SPEC.md`](../../SPEC.md)
> (§2.2/§2.4/§3.8, BR-21/BR-22, ADR-009),
> [`docs/handoff/E17-approver-routing.md`](../handoff/E17-approver-routing.md),
> [`docs/handoff/E5-workflow.md`](../handoff/E5-workflow.md),
> [`docs/adr/0010-workflow-topology-as-data.md`](../adr/0010-workflow-topology-as-data.md).
>
> **Дата.** 2026-05-19.
> **Ревьюер.** Claude Opus 4.7 (1M context).
> **Объект.** Изменения E17 (commit `6b7f9ed`, 34 файла) + архитектурный
> проход по проекту.
> **Метод.** Построчный разбор кода E17 (compliance-смежная зона workflow),
> сверка с инвариантами SPEC §3.8 / ADR-0010; эмпирика — e2e-smoke на
> чистом `make up` (адресация / негативы / owner-этап / publish — зелёный);
> `./bin/mvn -DskipITs verify` BUILD SUCCESS (StateMachineTest 22/22,
> WorkflowGraphInvariantsTest 13/13, ArchUnit 11/11); UI typecheck/build
> зелёные. ITs локально skip (Docker-in-Docker, by design — CI-авторитет).

---

## 0. Итог

E17 архитектурно корректен и **не ослабляет compliance-ядро**: `StateMachine`
и `WorkflowGraphInvariants` не изменены; self-approval и no-bypass 4-eyes
сохранены (адресат получает asset-роль только своей ступени, проверка «3
разных лица» осталась в неизменном ядре через `actor∈reviewers`). Перенос
assignee через ThreadLocal вместо смены сигнатуры `WorkflowEngine.transition`
— верный trade-off (Flowable-путь не задет). Обратная совместимость
сохранена (5-arg `WorkflowModule.build` / null-порт → legacy broadcast; ITs
не сломаны).

**Ни одна находка не является security-дырой или регрессом существующего
поведения.** Главное по значимости — **F3** (тестовый разрыв в
compliance-смежной зоне, усиленный CI-only-политикой ITs) и **F1**
(рукотворный тупик процесса).

---

## 1. Находки

| # | Severity | Файл / место | Суть | Рекомендация | Статус |
|---|---|---|---|---|---|
| **F1** | **Medium** | `rdmmesh-workflow/.../service/WorkflowService.java:299–325` (`validateAssignee`) | submit не отвергает `steward == owner`. Проверяется `steward≠createdBy`, `owner≠createdBy`, но не `steward≠owner`. Один человек на обе роли → апрувит как steward (становится reviewer) → на owner-этапе 409 SelfApproval из ядра. Тупик: delete+resubmit. 4-eyes цел (НЕ дыра), но рукотворная ловушка UX. | Ветка `steward.equals(owner)` в `validateAssignee` → понятная ошибка на submit (fail fast) + сообщение в UI submit-диалоге. | OPEN |
| **F3** | **Medium** | `rdmmesh-*/src/test` (нет E17-тестов) | Ноль автотестов на E17. Фича compliance-смежная; на репозитории ITs — CI-авторитет, локально гейтят только surefire/ArchUnit. Единственная верификация сейчас — ручной smoke. | (а) unit на `PostgresApproverDirectoryPort.reload` (truncate+insert, резолв домена, skip неизвестного) и на `validateAssignee`; (б) `PostgresIT`-based IT: адресный happy-path + негативы (нет assignee→400 / не в справочнике→409 / self→409) + F1 + доказательство no-bypass при адресном пути. | OPEN |
| **F4** | Low | `rdmmesh-ownership/.../internal/PostgresApproverDirectoryPort.java:60–62` | `reload` молча пропускает entries с незеркалированным `om_domain_id` (`INSERT…SELECT` → 0 строк). Ответ только `{received, inserted}` — оператор не видит, какие домены отброшены. | Логировать пропущенные `om_domain_id` (или вернуть `skipped[]`). | OPEN |
| **F5** | Low/Info | `rdmmesh-workflow/.../service/SubmitAssigneeHolder.java` | Корректность держится на инварианте «Flowable `trigger` синхронный, async off». Включат job-executor для шага → assignee тихо `null`, submit молча → broadcast без ошибки (потеря адресности). | IT с `RDM_WORKFLOW_ENGINE=flowable` на адресном submit, либо лог-assert при расхождении. | OPEN |
| **F6** | Low | `SPEC.md` §3.5 | Реальный путь reload — `/admin/domain-role-directory/reload`, в SPEC §3.5 — `:reload` (двоеточие). Деviation отмечен в шапке E17, но сам §3.5 не поправлен. | Привести SPEC §3.5 в соответствие (или явно пометить deviation там же). | OPEN |
| **F7** | Low | `WorkflowService.validateAssignee` | «Чужой домен в assignee» → 409 (workflow-конфликт), хотя это скорее невалидный ввод (400). Решение осознанное (E17 §10 q1). | Подтвердить error-таксономию (400 vs 409) с командой банка. | OPEN (вопрос бизнесу) |
| **F2** | ✓ verified | `rdmmesh-ownership/.../resource/DomainApproversResource.java` | Риск регрессии catalog `GET /domains/{id}` из-за нового root-resource `/domains/{domainId}/approvers`. Проверено: по JAX-RS root-resource выигрывает по специфичности; в smoke catalog-эндпоинты и approvers сосуществовали в одном прогоне. | Действий не требуется. | CLOSED |

Корректно реализовано (без замечаний): миграции с грантами (V062 `TRUNCATE`
для reload, V034), `additionalProperties:false` в spec-схеме, обратная
совместимость (5-arg build / null-порт), идемпотентный reload (full
replace), адресная `approval_task` с `assigned_role`, augment asset-роли
адресата только на его ступени.

---

## 2. Проект — архитектура и здоровье

**Сильные стороны (подтверждены):**
- Модульный монолит + ArchUnit-энфорсмент (11 правил, strict), schema-first,
  bitemporal с первого дня — выдержано и в E17.
- No-bypass workflow с двойным рубежом `WorkflowGraphInvariants` (deploy +
  runtime, defense-in-depth); E17 сознательно остался снаружи ядра.
- Безопасность: HMAC in/out, JWT на запрос, append-only audit + hash-chain +
  INSERT-only гранты, SSRF egress-guard. E17 ничего не ослабил (reload —
  RDM_ADMIN, approvers — authenticated, assignee валидируется на сервере,
  self-approval enforced, full-replace без эскалации).

**Системные риски (держать на виду; не регрессы E17):**
1. **ITs — только CI-авторитет** на этом хосте (Docker-in-Docker by design).
   Фичи в `main` без unit/ArchUnit-покрытия локально не гейтятся вовсе —
   усиливает F3. Самый важный follow-up.
2. **Split-tx authoring↔workflow:** статус+журнал+task в одной
   `jdbi.inTransaction` (E17 route-upsert туда же — правильно), но EventBus
   publish post-commit best-effort → audit может пропустить событие.
   Допустимо по SPEC §3.8, известная compliance-оговорка, не регресс E17.
3. **Helm не прогнан на стенде/линте** (из истории проекта);
   `om-rdmmesh-source` + OM-генерируемый справочник ролей ещё впереди —
   E17-справочник пока local-seed (ожидаемо, ADR-009 / E17 §7).

---

## 3. Приоритизированные рекомендации

1. **F3 (Medium, в первую очередь):** автотесты E17 — unit
   (`reload`/`validateAssignee`) + `PostgresIT`-IT (happy + негативы + F1 +
   no-bypass). До этого фича на `main` верифицирована только ручным smoke.
2. **F1 (Medium):** отвергать `steward == owner` на submit (одна ветка в
   `validateAssignee` + i18n в UI-диалоге).
3. **F4 / F5 (Low):** логировать пропущенные домены в `reload`;
   flowable-IT/assert для инварианта ThreadLocal.
4. **F6 (Low):** синхронизировать SPEC §3.5 с фактическим путём reload.
5. **F7:** подтвердить error-таксономию (400 vs 409) с командой банка
   (открытый вопрос E17 §10).

---

## 4. Версия документа

- **0.1** — 2026-05-19. Ревью E17 (commit `6b7f9ed`) + архитектурный проход.
  7 находок (F1–F7): 2 Medium (F1, F3), 4 Low (F4–F7), 1 verified-closed
  (F2). Ни одной security-дыры/регресса. Автор: Claude Opus 4.7.
