-- V074: реестр immutable-архива audit-сегментов (E14 round 10).
--
-- SPEC §3.8 — «Параллельный сток в S3 (immutable bucket) — V2». E14.7 §3 #2:
-- audit.drop_audit_partition_if_archived(p_partition, p_archived, ...) доверял
-- p_archived как честному параметру оператора — «проверки факта архива нет».
-- Round 10 закрывает это: факт архива фиксируется строкой в
-- audit.archive_manifest (пишется backend'ом ПОСЛЕ успешной заливки сегмента в
-- RustFS/S3), а новый 1-арг overload drop-функции выводит archived ИЗ манифеста,
-- а не из аргумента — honor-system устранён.
--
-- Манифест immutable по той же логике, что audit_log: одна финальная строка на
-- сегмент (UNIQUE segment_label, INSERT ... ON CONFLICT DO NOTHING), роль
-- rdmmesh_app получает только INSERT/SELECT (REVOKE UPDATE/DELETE/TRUNCATE).

CREATE TABLE IF NOT EXISTS audit.archive_manifest (
    id                bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- Имя партиции audit_log, которую покрывает сегмент (напр. audit_log_y2026m05).
    segment_label     text        NOT NULL UNIQUE,
    from_id           bigint      NOT NULL,
    to_id             bigint      NOT NULL,
    row_count         bigint      NOT NULL,
    -- SHA-256 детерминированно сериализованного сегмента (для offline-verify
    -- аудитором независимо от backend'а; та же дисциплина, что content_hash V072).
    content_sha256    text        NOT NULL CHECK (content_sha256 ~ '^[a-f0-9]{64}$'),
    bucket            text        NOT NULL,
    object_key        text        NOT NULL,
    etag              text,
    size_bytes        bigint,
    -- Удалось ли выставить S3 Object-Lock retention. false → объект записан,
    -- но WORM-гарантия не на стороне store'а (RustFS Object-Lock API не принял);
    -- честно фиксируем, чтобы не было молчаливой потери immutability.
    retention_applied boolean     NOT NULL DEFAULT false,
    retain_until      timestamptz,
    archived_by       uuid,
    archived_at       timestamptz NOT NULL DEFAULT now(),
    CHECK (to_id >= from_id),
    CHECK (row_count >= 0)
);

COMMENT ON TABLE audit.archive_manifest IS
    'E14 round 10: реестр immutable-архива сегментов audit_log в S3/RustFS. '
    'Источник истины для audit.drop_audit_partition_if_archived(text).';

-- ── Источник истины «сегмент заархивирован» ─────────────────────────────────────
CREATE OR REPLACE FUNCTION audit.assert_segment_archived(p_partition text)
RETURNS void LANGUAGE plpgsql AS $fn$
DECLARE
    m audit.archive_manifest;
BEGIN
    SELECT * INTO m FROM audit.archive_manifest WHERE segment_label = p_partition;
    IF NOT FOUND THEN
        RAISE EXCEPTION
            'сегмент % не заархивирован: нет строки в audit.archive_manifest '
            '(залить в immutable-store через backend перед дропом)', p_partition
            USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF NOT m.retention_applied THEN
        RAISE WARNING
            'сегмент % заархивирован, но Object-Lock retention НЕ подтверждён '
            '(retention_applied=false) — immutability только на bucket-policy', p_partition;
    END IF;
END
$fn$;

-- ── 1-арг overload: archived выводится из манифеста, не из аргумента ─────────────
-- Делегирует в замороженную V073 3-арг функцию (retention-window/DEFAULT/
-- not-found логика не дублируется), передав archived=true ТОЛЬКО если манифест
-- подтвердил факт. Это и есть «связать drop-guard с round 10» (E14.7 §3 #2).
CREATE OR REPLACE FUNCTION audit.drop_audit_partition_if_archived(p_partition text)
RETURNS void LANGUAGE plpgsql AS $fn$
BEGIN
    PERFORM audit.assert_segment_archived(p_partition);
    PERFORM audit.drop_audit_partition_if_archived(p_partition, true);
END
$fn$;

COMMENT ON FUNCTION audit.drop_audit_partition_if_archived(text) IS
    'E14 round 10: безопасный дроп — archived выводится из audit.archive_manifest '
    '(не honor-system аргумент). Делегирует в V073 3-арг функцию для '
    'retention-window/DEFAULT/not-found проверок.';

-- Ops-функции — owner-only (как ensure_audit_partition/3-арг drop в V073).
REVOKE EXECUTE ON FUNCTION audit.assert_segment_archived(text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION audit.drop_audit_partition_if_archived(text) FROM PUBLIC;

-- Манифест: app-роль пишет (после успешной заливки) и читает; без mutation.
DO $g$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'rdmmesh_app') THEN
        EXECUTE 'GRANT USAGE ON SCHEMA audit TO rdmmesh_app';
        EXECUTE 'GRANT SELECT, INSERT ON audit.archive_manifest TO rdmmesh_app';
        EXECUTE 'REVOKE UPDATE, DELETE, TRUNCATE ON audit.archive_manifest FROM rdmmesh_app';
    END IF;
END $g$;
