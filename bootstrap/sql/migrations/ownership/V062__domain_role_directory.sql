-- V062: ownership — справочник ролей домена для адресной маршрутизации
-- согласования (SPEC §2.4 «Справочник ролей домена», BR-21/BR-22, ADR-009,
-- handoff E17).
--
-- Это НЕ rdm_asset_ownership (тот — per-CodeSet, дельта-UPSERT по webhook'у
-- OM, V060) и НЕ catalog.domain. Это самостоятельный домен-скоупный
-- справочник кандидатов-согласующих: «домен → роль(STEWARD|BUSINESS_OWNER)
-- → учётная запись».
--
-- Семантика обновления — ПОЛНАЯ ЗАМЕНА (TRUNCATE + INSERT одной транзакцией,
-- см. ApproverDirectoryReloadService): справочник целиком приходит от
-- мастер-системы (OpenMetadata) как готовый снапшот. Дельта-UPSERT здесь
-- сознательно НЕ применяется (другая модель консистентности, чем у webhook'а
-- E7). На текущем этапе наполняется локальным сидом (source='LOCAL_SEED'),
-- позже — справочником, сгенерированным в OM (source='OM_GENERATED'),
-- без изменений downstream.
--
-- BUSINESS_OWNER домена == владелец домена в терминах OM (тот же субъект,
-- что приходит как OWNER для entity_type=domain), здесь — выбираемый по
-- домену согласующий.

CREATE TABLE ownership.domain_role_directory (
    id            uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    domain_id     uuid        NOT NULL REFERENCES catalog.domain(id),
    role          text        NOT NULL
        CHECK (role IN ('STEWARD', 'BUSINESS_OWNER')),
    om_user_id    uuid        NOT NULL,
    username      text        NOT NULL,
    display_name  text,
    source        text        NOT NULL DEFAULT 'LOCAL_SEED'
        CHECK (source IN ('LOCAL_SEED', 'OM_GENERATED')),
    loaded_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (domain_id, role, om_user_id)
);

CREATE INDEX domain_role_directory_domain_role_ix
    ON ownership.domain_role_directory (domain_id, role);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE, TRUNCATE'
              || ' ON ownership.domain_role_directory TO rdmmesh_app';
    END IF;
END$$;
