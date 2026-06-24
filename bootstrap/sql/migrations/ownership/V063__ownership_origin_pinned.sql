-- V063: ownership.rdm_asset_ownership — per-row provenance + pinned_local
-- (ADR-0010, E18 §2.2, §4 сценарий C).
--
-- До этой миграции каждый ряд считался OM-зеркалом — webhook E7 управлял
-- жизненным циклом полностью. Теперь admin может назначать ownership локально
-- через UI (POST /admin/domains/{id}/ownership, E18 §5.2), и таких рядов
-- OM-webhook не должен трогать.
--
-- Дискриминатор `origin`:
--   OM   — пришёл webhook'ом ENTITY_UPDATED.owners/experts/reviewers. Webhook
--          может обновлять / удалять такие ряды штатно (как и до V063).
--   RDM  — назначен admin'ом в UI RDM. Webhook НЕ трогает; при попытке OM убрать
--          этого пользователя из роли (delta-event) генерируется admin task
--          OWNERSHIP_OM_REMOVAL_CONFLICT (admin.resolution_task, см. V100).
--
-- `pinned_local` (флаг, ортогональный origin'у):
--   true  — даже OM-origin ряд защищён от воскрешения/удаления webhook'ом.
--           Сценарий C.3 (E18 §4): admin локально удалил OM-ряд, не хочет чтобы
--           следующий OM-event его восстановил. Webhook видит pinned_local=true
--           и пишет конфликт в catalog.domain_conflict_log (resolution=SKIPPED)
--           вместо UPDATE/INSERT.
--   false — дефолт, обычная webhook-управляемая запись.
--
-- `superseded_at` — soft-delete для аудита: ряд не удаляется физически при
-- OM-removal, а помечается superseded_at; permission cache использует
-- WHERE superseded_at IS NULL. Это даёт возможность увидеть исторические
-- назначения и восстановить их одним UPDATE при ошибочном webhook'е.

ALTER TABLE ownership.rdm_asset_ownership
    ADD COLUMN origin              text        NOT NULL DEFAULT 'OM'
        CHECK (origin IN ('OM', 'RDM')),
    ADD COLUMN pinned_local        boolean     NOT NULL DEFAULT false,
    ADD COLUMN assigned_by_user_id uuid,
    ADD COLUMN superseded_at       timestamptz;

-- Поиск активных (не вытесненных) ownership-tuple'ов. Существующий
-- rdm_asset_ownership_asset_ix (asset_id, asset_type) остаётся первичным
-- индексом; этот частичный — для горячего пути permission cache invalidation
-- после webhook'а.
CREATE INDEX rdm_asset_ownership_active_ix
    ON ownership.rdm_asset_ownership (asset_id, asset_type, role)
    WHERE superseded_at IS NULL;

-- RDM-origin ряды admin'у нужно быстро видеть в UI «локальные назначения».
CREATE INDEX rdm_asset_ownership_rdm_origin_ix
    ON ownership.rdm_asset_ownership (asset_id, asset_type)
    WHERE origin = 'RDM' AND superseded_at IS NULL;

-- Pinned-local — горячая проверка webhook'а перед UPDATE: «можно ли мне это
-- трогать?» Очень мало рядов, частичный индекс эффективен.
CREATE INDEX rdm_asset_ownership_pinned_ix
    ON ownership.rdm_asset_ownership (asset_id, asset_type, om_user_id, role)
    WHERE pinned_local = true;

COMMENT ON COLUMN ownership.rdm_asset_ownership.origin IS
    'Кто создал ряд: OM (webhook E7) или RDM (admin через UI, E18). OM-webhook не трогает RDM-origin ряды; вместо этого генерирует admin task в admin.resolution_task.';
COMMENT ON COLUMN ownership.rdm_asset_ownership.pinned_local IS
    'Если true — webhook OM не может ни обновить, ни воскресить этот ряд (даже origin=OM). Расхождения с OM пишутся в catalog.domain_conflict_log как SKIPPED.';
COMMENT ON COLUMN ownership.rdm_asset_ownership.assigned_by_user_id IS
    'OM user id админа, который назначил ряд через UI (origin=RDM). NULL для origin=OM.';
COMMENT ON COLUMN ownership.rdm_asset_ownership.superseded_at IS
    'Soft-delete: ряд снят с активности но не удалён физически. Permission cache фильтрует WHERE superseded_at IS NULL.';
