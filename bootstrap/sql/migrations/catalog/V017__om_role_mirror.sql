-- V017: catalog.om_role — зеркало ролей из OpenMetadata.
--
-- Часть интеграции OM→rdmmesh (thin-notification + pull): OM Alert
-- `Domain_and_Roles_sync_for_RDMmesh` шлёт тонкое уведомление на
-- POST /webhooks/om/catalog-sync, после чего RDM сам тянет домены и роли из
-- нативного OM REST API и апсертит сюда. Owner module: rdmmesh-catalog.
--
-- Идентичность роли — om_role_id (system of record в OM). Идемпотентность апсерта
-- по om_role_id. Soft-delete (deleted_at) — на случай, если роль исчезнет в OM.

CREATE TABLE catalog.om_role (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    om_role_id      uuid        NOT NULL UNIQUE,
    name            text        NOT NULL,
    display_name    text,
    description     text,
    last_om_sync_at timestamptz NOT NULL DEFAULT now(),
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),
    deleted_at      timestamptz
);

COMMENT ON TABLE catalog.om_role IS
    'Зеркало ролей OpenMetadata (DomainOwner, DomainDataSteward, ...). Наполняется pull''ом по уведомлению OM Alert Domain_and_Roles_sync_for_RDMmesh.';

-- Те же grant''ы, что и для остальных таблиц схемы catalog (см. V010).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON catalog.om_role TO rdmmesh_app';
    END IF;
END$$;
