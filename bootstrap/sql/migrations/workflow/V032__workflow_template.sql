-- V032: реестр per-domain BPMN-шаблонов workflow (V2 / BR-18 round 2,
-- ADR-0009 «Flowable tenancy + наш audit»).
--
-- Топология (какой BPMN приводит в действие 4-eyes) задаётся per Domain
-- и деплоится в Flowable с tenantId=domain_id. ЭТА таблица — наш
-- append-only-по-смыслу реестр/аудит: кто, когда, какой BPMN (sha256),
-- какая версия был активен для домена → воспроизводимость (SPEC §2.3,
-- регуляторное требование Risk/IFRS9). Строки не удаляются; смена
-- шаблона = новая строка (version++), прежняя active=false.
--
-- Авторитет легальности/guard'ов остаётся в enum-StateMachine
-- (WorkflowService) — модель A (ADR-0009 round 2): кастомная топология
-- НЕ может обойти 4-eyes по построению. Тут — только привязка+аудит.

CREATE TABLE workflow.workflow_template (
    id                     uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    domain_id              uuid        NOT NULL,
    -- BPMN process id (ключ) задеплоенного определения для этого домена.
    process_key            text        NOT NULL,
    -- Flowable deployment id (ACT_RE_DEPLOYMENT) — для трассировки.
    flowable_deployment_id text        NOT NULL,
    -- SHA-256 загруженного BPMN-XML (воспроизводимость / целостность).
    bpmn_sha256            text        NOT NULL CHECK (bpmn_sha256 ~ '^[a-f0-9]{64}$'),
    -- Монотонная версия per domain (1,2,3…).
    version                integer     NOT NULL CHECK (version >= 1),
    deployed_by            uuid        NOT NULL,
    deployed_at            timestamptz NOT NULL DEFAULT now(),
    active                 boolean     NOT NULL DEFAULT true
);

-- Не более одного активного шаблона на домен.
CREATE UNIQUE INDEX workflow_template_one_active_per_domain
    ON workflow.workflow_template (domain_id)
    WHERE active;

-- История версий домена + быстрый lookup активного.
CREATE INDEX workflow_template_domain_ix
    ON workflow.workflow_template (domain_id, version DESC);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE ON workflow.workflow_template TO rdmmesh_app';
    END IF;
END$$;
