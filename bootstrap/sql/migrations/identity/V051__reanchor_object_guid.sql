-- V051: re-anchor identity mapping from keycloak_sub to AD objectGUID.
--
-- Phase 1 of the identity/role redesign (см. project_identity_role_redesign).
--
-- Что меняется и почему:
--   * object_guid (AD objectGUID, claim `oid`) становится СТАБИЛЬНЫМ якорём строки.
--     keycloak_sub волатилен (регенерируется при пере-импорте realm / re-провижининге
--     федерации), поэтому из ключа идентичности он выводится в обычный атрибут.
--   * om_user_id становится NULLABLE: viewer без DG-роли в OM не имеет om_user_id
--     (read-only, истории не порождает). Provisional UUID v5 от sub удаляется на
--     уровне кода — здесь снимаем NOT NULL и PK с om_user_id.
--   * Натуральный ключ реконсиляции остаётся username (sAMAccountName) — стабилен и
--     уникален на личность; surrogate PK `id` добавляется, т.к. om_user_id больше не PK.

ALTER TABLE identity.rdm_user_mapping ADD COLUMN object_guid uuid;

-- Бэкофилл существующих строк временным GUID: на первом логине после миграции
-- upsert (ON CONFLICT username) перезапишет его реальным `oid` из токена.
UPDATE identity.rdm_user_mapping SET object_guid = gen_random_uuid() WHERE object_guid IS NULL;
ALTER TABLE identity.rdm_user_mapping ALTER COLUMN object_guid SET NOT NULL;
ALTER TABLE identity.rdm_user_mapping ADD CONSTRAINT rdm_user_mapping_object_guid_key UNIQUE (object_guid);

-- Surrogate PK взамен PK на om_user_id.
ALTER TABLE identity.rdm_user_mapping ADD COLUMN id uuid NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE identity.rdm_user_mapping DROP CONSTRAINT rdm_user_mapping_pkey;
ALTER TABLE identity.rdm_user_mapping ADD CONSTRAINT rdm_user_mapping_pkey PRIMARY KEY (id);

-- om_user_id: nullable + UNIQUE (несколько NULL разрешено — viewer'ы без OM-роли).
ALTER TABLE identity.rdm_user_mapping ALTER COLUMN om_user_id DROP NOT NULL;
ALTER TABLE identity.rdm_user_mapping ADD CONSTRAINT rdm_user_mapping_om_user_id_key UNIQUE (om_user_id);

-- keycloak_sub: понижается до обычного атрибута (nullable, без UNIQUE).
ALTER TABLE identity.rdm_user_mapping ALTER COLUMN keycloak_sub DROP NOT NULL;
ALTER TABLE identity.rdm_user_mapping DROP CONSTRAINT rdm_user_mapping_keycloak_sub_key;
