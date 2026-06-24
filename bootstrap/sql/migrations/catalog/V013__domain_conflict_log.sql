-- V013: catalog.domain_conflict_log — field-level аудит расхождений между
-- OM-payload'ом и текущим состоянием RDM при LINKED-доменах (ADR-0010, E18 §4).
--
-- Когда webhook OM приходит для domain'а с master='LINKED', а у нас поле
-- (description / label_ru / label_en / tags) присутствует в local_overrides,
-- решение всегда «RDM wins»: значение OM не применяется, но записывается СЮДА.
-- Admin может в UI увидеть полный лог расхождений за период (E18 §5.2,
-- GET /admin/conflict-log).
--
-- Решения LINKED-домена по name/parent — всегда «OM wins», запись в этот лог
-- НЕ делается (нет конфликта — OM авторитетен по этим полям всегда).
--
-- Сюда же пишутся события C.2 (OM хочет удалить ownership-tuple, у нас origin=RDM
-- или pinned_local=true) — для read-only исторического обзора, ПАРАЛЛЕЛЬНО созданию
-- admin.resolution_task. Resolve-задачи живут в admin schema; этот лог — append-only
-- архив для compliance/аудита.

CREATE TABLE catalog.domain_conflict_log (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    domain_id         uuid        NOT NULL REFERENCES catalog.domain (id),
    source_event_id   text,
    field             text        NOT NULL,
    om_value          jsonb,
    rdm_value         jsonb,
    applied_value     jsonb,
    resolution        text        NOT NULL
        CHECK (resolution IN ('OM_WINS', 'RDM_WINS', 'MANUAL', 'SKIPPED')),
    actor             uuid,
    applied_at        timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX domain_conflict_log_domain_ix
    ON catalog.domain_conflict_log (domain_id, applied_at DESC);

CREATE INDEX domain_conflict_log_resolution_ix
    ON catalog.domain_conflict_log (resolution, applied_at DESC);

COMMENT ON TABLE catalog.domain_conflict_log IS
    'Append-only audit field-level конфликтов OM↔RDM. Не имеет триггеров запрещающих UPDATE/DELETE на DB-уровне (в отличие от audit.audit_log) — это catalog-аудит, не compliance-журнал. Для compliance-нужд та же информация эмитится в DomainEvent → audit.audit_log через EventBus (см. E10).';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT SELECT, INSERT ON catalog.domain_conflict_log TO rdmmesh_app';
    END IF;
END$$;
