-- V024: relational store (spike) — отказ от JSONB в пользу физической таблицы на справочник.
--
-- Каждый CodeSet материализуется в реальную типизированную таблицу в схеме `rd_data`:
-- по колонке на каждую key-part и на каждый атрибут (тип выводится из key_spec и
-- CodeSetSchema), строки — настоящие строки «ячейка за ячейкой». Это альтернатива
-- generic authoring.code_item с jsonb key_parts/attributes.
--
-- Модель версионности — вариант C (см. docs/handoff/spike-relational-codesets.md):
-- на справочник две таблицы в rd_data, производные от базового имени <domain>__<codeset>:
--   "<base>__draft"   — рабочая область авторинга, PK (version_id, <ключи>);
--   "<base>__current" — текущий PUBLISHED-снапшот, PK (<ключи>) — цель настоящих FK.
-- На publish "<base>__current" атомарно пересобирается из draft нужной версии.
--
-- Stage 2 (этот шаг): write-path + publish реляционного стора. Полное вытеснение
-- jsonb из authoring/publishing/distribution — последующие стадии.

CREATE SCHEMA IF NOT EXISTS rd_data;

-- Реестр: какой CodeSet в какое БАЗОВОЕ имя таблицы материализован + что сейчас опубликовано.
-- Базовое имя ≤54, чтобы "<base>__current" (+9) укладывалось в лимит идентификатора PG (63).
CREATE TABLE authoring.codeset_physical_table (
    codeset_id            uuid        PRIMARY KEY,
    schema_name           text        NOT NULL DEFAULT 'rd_data',
    table_name            text        NOT NULL
        CHECK (table_name ~ '^[a-z][a-z0-9_]{0,53}$'),
    schema_version        integer     NOT NULL,
    published_version_id  uuid,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),
    UNIQUE (schema_name, table_name)
);

-- ── grants ────────────────────────────────────────────────────────────────────
-- rdmmesh_app должен уметь CREATE TABLE в rd_data в рантайме (DDL генерируется
-- из key_spec+схемы) и читать/писать как реестр, так и созданные таблицы.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT USAGE, CREATE ON SCHEMA rd_data TO rdmmesh_app';
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA rd_data TO rdmmesh_app';
        EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA rd_data GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO rdmmesh_app';
        -- реестр живёт в authoring; на эту схему грант уже выдан в V020, но новый
        -- объект подхватит дефолтные привилегии только если они заданы — подстрахуемся.
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON authoring.codeset_physical_table TO rdmmesh_app';
    END IF;
END$$;
