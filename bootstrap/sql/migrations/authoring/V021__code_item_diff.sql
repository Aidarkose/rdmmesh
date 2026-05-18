-- V021: SQL building block for version-to-version diff (SPEC §3.5 / §2.2 etap 2).
--
-- Returns one row per CodeItem key that exists in either version. The Java diff layer
-- computes `changed_fields` (JSON paths inside CodeItem) on top of the raw before/after
-- payloads — keeping this function dumb and composable.
--
-- `op` semantics:
--   ADDED    — key exists only in the "to" version
--   REMOVED  — key exists only in the "from" version
--   CHANGED  — key exists in both, payload differs
--   UNCHANGED— key exists in both, payload identical (caller filters these out)
--
-- "MOVED" (parent_key changed but rest equal) is detected by the Java layer because it
-- is a refinement of CHANGED rather than a separate row-level state.

CREATE OR REPLACE FUNCTION authoring.code_item_diff_base(
        from_version_id uuid,
        to_version_id   uuid)
    RETURNS TABLE (
        op          text,
        key_parts   jsonb,
        before_doc  jsonb,
        after_doc   jsonb)
    LANGUAGE sql STABLE AS $$
    WITH
      lhs AS (
        SELECT key_parts, jsonb_build_object(
                 'label_ru',       label_ru,
                 'label_en',       label_en,
                 'description_ru', description_ru,
                 'description_en', description_en,
                 'parent_key',     parent_key,
                 'parent_ref',     parent_ref,
                 'attributes',     attributes,
                 'order_index',    order_index,
                 'status',         status,
                 'effective_from', to_jsonb(effective_from),
                 'effective_to',   to_jsonb(effective_to)) AS doc
          FROM authoring.code_item
         WHERE version_id = from_version_id),
      rhs AS (
        SELECT key_parts, jsonb_build_object(
                 'label_ru',       label_ru,
                 'label_en',       label_en,
                 'description_ru', description_ru,
                 'description_en', description_en,
                 'parent_key',     parent_key,
                 'parent_ref',     parent_ref,
                 'attributes',     attributes,
                 'order_index',    order_index,
                 'status',         status,
                 'effective_from', to_jsonb(effective_from),
                 'effective_to',   to_jsonb(effective_to)) AS doc
          FROM authoring.code_item
         WHERE version_id = to_version_id)
    SELECT
        CASE
            WHEN lhs.doc IS NULL THEN 'ADDED'
            WHEN rhs.doc IS NULL THEN 'REMOVED'
            WHEN lhs.doc IS DISTINCT FROM rhs.doc THEN 'CHANGED'
            ELSE 'UNCHANGED'
        END                                              AS op,
        COALESCE(rhs.key_parts, lhs.key_parts)           AS key_parts,
        lhs.doc                                          AS before_doc,
        rhs.doc                                          AS after_doc
      FROM lhs FULL OUTER JOIN rhs USING (key_parts);
$$;

COMMENT ON FUNCTION authoring.code_item_diff_base(uuid, uuid) IS
    'Row-level diff between two CodeSetVersions. Returns one row per key in either version. '
    'The application layer filters UNCHANGED out and computes per-field changed_fields.';

-- Grants — only SELECT is needed for the application role (function reads, no writes).
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT EXECUTE ON FUNCTION authoring.code_item_diff_base(uuid, uuid) TO rdmmesh_app';
    END IF;
END$$;
