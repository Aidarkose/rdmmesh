-- V031: изолированная схема для BPMN-движка Flowable (V2 / BR-18, ADR-009).
--
-- АРХИТЕКТУРНЫЙ CARVE-OUT (документированное исключение из инварианта
-- E1 §1.3 / SPEC §3.2 «Flyway — единственный DDL-авторитет»):
-- ~25 ACT_*-таблиц Flowable создаёт и мигрирует САМ (встроенный Liquibase,
-- databaseSchemaUpdate=true) в этой схеме. Flyway лишь ВЛАДЕЕТ фактом её
-- существования и грантами — но НЕ структурой таблиц движка (привязка к
-- внутренней схеме Flowable была бы хрупкой на апгрейдах движка).
--
-- Схема намеренно НЕ в Flyway `schemas`-списке: Flyway её не «чистит» и
-- flyway_schema_history тут не живёт. Flowable подключается под ролью
-- rdmmesh_app (та же, что у приложения) и становится владельцем ACT_*.
--
-- Движок in-process на том же Postgres — НЕ новый внешний компонент
-- (SPEC §3.1 lean: по-прежнему Postgres + Keycloak + JVM).

CREATE SCHEMA IF NOT EXISTS workflow_engine;

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        -- USAGE + CREATE: Flowable/Liquibase создаёт свои ACT_*-таблицы
        -- здесь при первом старте движка (rdmmesh_app — owner созданного).
        EXECUTE 'GRANT USAGE, CREATE ON SCHEMA workflow_engine TO rdmmesh_app';
    END IF;
END$$;
