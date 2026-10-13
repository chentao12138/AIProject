-- V059__asset_versioning_and_folder_sync_counts.sql
--
-- F-12 (ZIP entry idempotency) + F-13 (duplicate-upload policy) +
-- F-14 (folder sync completion chain) — lane-3 schema step.
--
-- WHY THERE IS NO UNIQUE INDEX ON (space_id, source_id,
-- original_relative_path, sha256):
--   V008 deliberately made sha256 non-unique (see its header comment)
--   and idx_source_asset_space_sha256 is a plain KEY; every ZIP retry
--   since then re-extracted the still-present package and inserted
--   duplicate (path, sha256) rows. A UNIQUE index created over that
--   existing data would FAIL the migration, and the only backfill that
--   would make it succeed (deleting the duplicate rows) breaks FK
--   references from folder_import_entry.source_asset_id and
--   source_page.asset_id on live databases. The idempotency rule is
--   therefore enforced in the SERVICE (select-by-(space,source,path,
--   sha) before insert + compensating delete of the just-stored
--   duplicate), with the SELECT-FOR-UPDATE space-row lock (below) as
--   the DB-side serialization point. A UNIQUE index can be revisited
--   in a later lane once the duplicate history is deduplicated with an
--   FK-safe migration.
--
-- asset_version / superseded_at (F-12 versioning rule):
--   re-extracting an entry whose (path, sha256) already exists REUSES
--   that row (zero new rows, zero new bytes). The same path with
--   DIFFERENT bytes is an explicit new version: a new row with
--   asset_version = max + 1 and the previous head row stamped
--   superseded_at. Nothing is deleted — supersede-and-retain keeps
--   provenance (pages/blocks/entries bound to the old row stay valid).
--   The "active head" of a path is the row with the highest
--   asset_version that has NOT been superseded; if all rows of a path
--   are superseded (should not happen), the highest version wins.
--   Existing rows backfill to asset_version = 1 via the DEFAULT.
--
-- idx_source_asset_space_source_path:
--   covers the idempotency lookup `WHERE space_id = ? AND source_id =
--   ? AND original_relative_path = ?` (prefix 255 chars keeps the key
--   inside InnoDB's 3072-byte limit under utf8mb4). Rows with NULL
--   path (plain uploads) are not indexed meaningfully and not affected.
--
-- folder_import_snapshot changed_files / pending_files (F-14):
--   the second-sync diff distinguishes ADDED (new path) from CHANGED
--   (known path, new sha); entries without bound bytes after submit
--   are PENDING until the client uploads + binds them. Defaults 0 keep
--   every existing snapshot row valid.

ALTER TABLE source_asset
    ADD COLUMN asset_version  INT         NOT NULL DEFAULT 1
        COMMENT 'ZIP re-extraction version of one (space, source, relative path); 1 = first seen',
    ADD COLUMN superseded_at  DATETIME(6) NULL
        COMMENT 'set when a NEWER version superseded this row; NULL = active head';

CREATE INDEX idx_source_asset_space_source_path
    ON source_asset (space_id, source_id, original_relative_path(255));

ALTER TABLE folder_import_snapshot
    ADD COLUMN changed_files INT NOT NULL DEFAULT 0
        COMMENT 'entries whose path existed in the previous snapshot with a different sha256',
    ADD COLUMN pending_files  INT NOT NULL DEFAULT 0
        COMMENT 'ADDED/CHANGED entries still awaiting an uploaded+bound asset';
