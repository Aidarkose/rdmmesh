-- V018: иерархия доменов (домены/поддомены из OpenMetadata).
--
-- Phase 2 редизайна (см. project_identity_role_redesign / handoff identity-objectguid-redesign).
--
-- OM — мастер иерархии (по V012: name/parent — OM wins). Храним ссылку на родителя
-- ПО OM-id, а не как локальный FK на catalog.domain(id): при ресинке родитель и потомок
-- приходят в произвольном порядке, и FK на локальный id ломал бы вставку потомка раньше
-- родителя. parent_om_domain_id резолвится в локальный id обычным self-join'ом.
-- NULL parent = корневой домен.

ALTER TABLE catalog.domain ADD COLUMN parent_om_domain_id uuid;

CREATE INDEX domain_parent_om_id_ix ON catalog.domain (parent_om_domain_id);

COMMENT ON COLUMN catalog.domain.parent_om_domain_id IS
    'om_domain_id родительского домена (OM — мастер иерархии). NULL = корневой домен. '
    'Резолвится в локальный id через self-join по om_domain_id.';
