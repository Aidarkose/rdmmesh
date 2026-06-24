-- V011: catalog.domain — soft-delete колонка для ENTITY_SOFT_DELETED событий из OM (SPEC §2.4).
--
-- Domain не удаляется физически: downstream-CodeSet'ы могут на него ссылаться (FK
-- catalog.code_set.domain_id ON DELETE RESTRICT). Webhook receiver (E7) при получении
-- ENTITY_SOFT_DELETED проставляет deleted_at; reads (DomainResource.findAll/findById)
-- продолжают возвращать запись — пометка нужна только для UI-баннера и регуляторного
-- audit'а. Воскрешение domain'а — обычным UPSERT (deleted_at IS NULL после ENTITY_UPDATED).

ALTER TABLE catalog.domain
    ADD COLUMN deleted_at timestamptz;

CREATE INDEX domain_deleted_at_ix
    ON catalog.domain (deleted_at)
    WHERE deleted_at IS NOT NULL;
