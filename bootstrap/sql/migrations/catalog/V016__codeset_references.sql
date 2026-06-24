-- V016: cross-codeset foreign-key references.
--
-- A CodeSet may declare FK-style links from one of its columns (a key part or an
-- attribute) to a column of another CodeSet — possibly in another domain. These are
-- surfaced to the OpenMetadata catalog as FOREIGN_KEY table constraints so consumers
-- can see related reference tables side by side and navigate between them.
--
-- Stored as JSONB array of { from_column, to_codeset_id, to_column, label? }. The column
-- is named `column_refs` (not `references`, which is a reserved SQL word). No referential
-- integrity is enforced at the DB level on purpose: targets may be cross-domain and the
-- relationship is descriptive metadata (see spec entity/code-set.json#/properties/references).

ALTER TABLE catalog.code_set
    ADD COLUMN column_refs jsonb NOT NULL DEFAULT '[]'::jsonb;
