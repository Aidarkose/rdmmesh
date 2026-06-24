-- V015 (E22): Author→Admin запросы на удаление CodeSet'а с обязательным reason.
--
-- Workflow: PENDING → APPROVED | REJECTED | CANCELLED (terminal).
-- Approve вызывает soft-delete catalog.code_set (deleted_at = now()).
-- PUBLISHED-версии не трогаем — IFRS9 retention §3.7.
--
-- См. docs/handoff/E22-deletion-requests.md.

CREATE TABLE catalog.code_set_deletion_request (
    id               uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    codeset_id       uuid        NOT NULL REFERENCES catalog.code_set(id) ON DELETE CASCADE,
    requested_by     uuid        NOT NULL,
    reason           text        NOT NULL
        CHECK (length(reason) BETWEEN 10 AND 2000),
    status           text        NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    decided_by       uuid,
    decision_comment text
        CHECK (decision_comment IS NULL OR length(decision_comment) <= 2000),
    created_at       timestamptz NOT NULL DEFAULT now(),
    decided_at       timestamptz,
    -- decided_at IS NULL <=> PENDING (xor с terminal-статусами).
    CONSTRAINT cs_del_req_decided_consistency
        CHECK ((status = 'PENDING') = (decided_at IS NULL)),
    -- REJECTED обязательно с комментарием. APPROVED/CANCELLED — комментарий опционален.
    CONSTRAINT cs_del_req_reject_needs_comment
        CHECK (status <> 'REJECTED' OR decision_comment IS NOT NULL)
);

-- Один PENDING на CodeSet одновременно — partial unique index.
CREATE UNIQUE INDEX cs_del_req_one_pending_per_codeset_ix
    ON catalog.code_set_deletion_request (codeset_id)
    WHERE status = 'PENDING';

-- Admin queue: filter by status, по умолчанию PENDING.
CREATE INDEX cs_del_req_status_ix
    ON catalog.code_set_deletion_request (status, created_at);

-- "Мои заявки" Author'а.
CREATE INDEX cs_del_req_requested_by_ix
    ON catalog.code_set_deletion_request (requested_by, created_at);

COMMENT ON TABLE  catalog.code_set_deletion_request
    IS 'E22: Author→Admin запросы на soft-delete CodeSet с обоснованием. См. docs/handoff/E22.';
COMMENT ON COLUMN catalog.code_set_deletion_request.reason
    IS 'Обязательное объяснение Author''а, 10..2000 символов. Попадает в audit log.';
COMMENT ON COLUMN catalog.code_set_deletion_request.decision_comment
    IS 'Комментарий Admin''а. Обязателен при REJECTED, опционален при APPROVED/CANCELLED.';
