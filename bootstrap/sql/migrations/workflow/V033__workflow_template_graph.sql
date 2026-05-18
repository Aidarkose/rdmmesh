-- V033: per-domain граф топологии в реестре шаблонов (V2 / BR-18,
-- ADR-0010 вариант B, слайс B2).
--
-- BPMN домена может нести extension-элемент <rdm:workflowGraph> с JSON
-- описанием рёбер (from,to,action,kind,…). При деплое он валидируется
-- WorkflowGraphInvariants (compliance-сеть — обязательный gate, →400) и
-- канонический JSON кладётся сюда. NULL = домен использует дефолтный
-- 4-eyes-граф (обратная совместимость с шаблонами round 2 без графа).
--
-- WorkflowService на каждом переходе резолвит активный graph_json домена
-- версии и валидирует переход против НЕГО (а не дефолта). Tampered/битый
-- JSON → fail-safe к дефолту (строжайший known-good, не ослабление).

ALTER TABLE workflow.workflow_template
    ADD COLUMN graph_json text;

COMMENT ON COLUMN workflow.workflow_template.graph_json IS
    'Канонический JSON WorkflowGraph (ADR-0010 B2). NULL = дефолтный 4-eyes. '
    'Прошёл WorkflowGraphInvariants при деплое; re-validated на чтении.';
