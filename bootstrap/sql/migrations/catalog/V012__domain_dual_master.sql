-- V012: catalog.domain — переход на dual-mastership (ADR-0010, supersedes ADR-008).
--
-- До этой миграции catalog.domain был чистым OM-зеркалом: om_domain_id NOT NULL,
-- запись создавалась только webhook'ом OM ENTITY_CREATED (E7). RDM_ADMIN не мог
-- создавать домены через UI; SPEC §2.4 разрешал «bootstrap REST» как технический
-- костыль, не как продуктовую возможность.
--
-- Новая модель: каждый ряд явно помечен `master ∈ {OM, RDM, LINKED}`:
--   OM     — pure mirror; UI read-only; OM-webhook авторитетен по всем полям.
--   RDM    — создан admin'ом локально; нет om_domain_id; OM-webhook'у нечего
--            делать (нет матча по UUID). При совпадении по имени webhook receiver
--            пишет в admin.resolution_task (см. V100), а не создаёт mirror.
--   LINKED — RDM-локальный домен явно слинкован с OM-доменом; field-level правила
--            (см. E18 §4): name/parent — OM wins; description/label_* — local_overrides
--            могут перебить OM (RDM wins для перечисленных в local_overrides ключей).
--
-- Идентификация: PK = catalog.domain.id (RDM UUID v4) НЕ МЕНЯЕТСЯ. Все существующие
-- FK (catalog.code_set.domain_id и др.) продолжают работать. om_domain_id остаётся
-- secondary unique для матчинга webhook → row. Матчинг по имени запрещён политикой
-- (см. E18 §3) — webhook ищет ТОЛЬКО по om_domain_id.
--
-- Backward compat: дефолты подобраны так, что ВСЕ существующие ряды получат
-- master='OM' и нулевые local_overrides/external_refs — поведение до этой
-- миграции (pure OM mirror) полностью сохранено. Никакого backfill'а не нужно.

ALTER TABLE catalog.domain
    ALTER COLUMN om_domain_id DROP NOT NULL;

ALTER TABLE catalog.domain
    ADD COLUMN master            text        NOT NULL DEFAULT 'OM'
        CHECK (master IN ('OM', 'RDM', 'LINKED')),
    ADD COLUMN local_overrides   jsonb       NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN external_refs     jsonb       NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN last_om_sync_at   timestamptz,
    ADD COLUMN deleted_in_om_at  timestamptz;

-- Инвариант master ↔ om_domain_id:
--   master='OM'     ⇒ om_domain_id NOT NULL (зеркало существующего OM-домена)
--   master='LINKED' ⇒ om_domain_id NOT NULL (явно слинкован с OM-доменом)
--   master='RDM'    ⇒ om_domain_id IS NULL  (создан локально, OM не знает)
-- Если admin переводит RDM → LINKED, он одновременно проставляет om_domain_id
-- (single atomic UPDATE), иначе CHECK провалит транзакцию.
ALTER TABLE catalog.domain
    ADD CONSTRAINT domain_master_id_consistency CHECK (
        (master = 'OM'     AND om_domain_id IS NOT NULL) OR
        (master = 'LINKED' AND om_domain_id IS NOT NULL) OR
        (master = 'RDM'    AND om_domain_id IS NULL)
    );

-- Частичный индекс на RDM-локальные: admin'у нужно быстро видеть «что не из OM»
-- (filter в UI Admin → Domains, ?master=RDM). Покрытие WHERE гарантирует малый
-- размер при ожидании что подавляющее большинство строк = 'OM'.
CREATE INDEX domain_master_rdm_ix
    ON catalog.domain (created_at DESC)
    WHERE master = 'RDM';

CREATE INDEX domain_master_linked_ix
    ON catalog.domain (last_om_sync_at DESC)
    WHERE master = 'LINKED';

COMMENT ON COLUMN catalog.domain.master IS
    'Ownership класс: OM (зеркало), RDM (локально создан admin''ом), LINKED (RDM↔OM связан). См. ADR-0010, handoff E18.';
COMMENT ON COLUMN catalog.domain.local_overrides IS
    'JSONB карта: { "<field>": <value> } полей, где RDM перебивает OM при LINKED. Допустимые ключи — только description/label_ru/label_en/tags (см. E18 §4 матрица).';
COMMENT ON COLUMN catalog.domain.external_refs IS
    'JSONB карта внешних идентификаторов: { "om": "<uuid>", "former_om": "<uuid после unlink>", "ad_ou": "..." }. Расширяемо без DDL.';
COMMENT ON COLUMN catalog.domain.last_om_sync_at IS
    'Timestamp последнего успешного webhook-апдейта от OM. NULL для master=RDM.';
COMMENT ON COLUMN catalog.domain.deleted_in_om_at IS
    'OM прислал ENTITY_DELETED для LINKED-домена. RDM не удаляет ряд (codeset''ы продолжают работать); admin вручную решает через admin.resolution_task task_type=DOMAIN_DELETED_IN_OM.';
