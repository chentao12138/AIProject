-- V008__create_source_asset.sql
--
-- BUSINESS-004 — production SourceAsset table (RAW file objects).
--
-- A SourceAsset is one actual file object belonging to a Source
-- (docs/data-model.md §5.2): the original ZIP (ORIGINAL_PACKAGE) or
-- an original PDF/image/markdown/text file (ORIGINAL_FILE) uploaded
-- by the space owner. BUSINESS-004 stores RAW bytes via
-- StorageService (ADR-033) and this metadata row; no ingestion, no
-- unzip, no OCR, no page generation yet.
--
-- Design notes:
--   * space_id + source_id: FK chain source_asset -> source ->
--     learning_space. Deliberately NO owner_subject column —
--     ownership is derived through the parent chain (BUSINESS-002
--     pattern). The FK alone cannot prove
--     source_asset.space_id == source.space_id, so every read is an
--     owner-scoped JOIN and upload validates the parent source via
--     SourceService.getMine() FIRST (BUSINESS-004 D1/D2).
--   * FKs use default RESTRICT (no CASCADE), matching V004/V005/
--     V006/V007. Never relax with CASCADE or FOREIGN_KEY_CHECKS=0.
--   * storage_key: cross-platform logical key (yyyy/MM/<uuid>),
--     NEVER a filesystem absolute path (ADR-033, NFR-DATA-003).
--     UNIQUE: each stored object maps to exactly one row.
--   * sha256 CHAR(64) hex, NOT unique: the same bytes may be
--     imported again by a future business policy (NFR-DATA-002
--     dedupe is a future decision); the (space_id, sha256) index
--     supports future dedupe lookups without enforcing it.
--   * asset_role: server-derived — ORIGINAL_PACKAGE for .zip,
--     ORIGINAL_FILE for the V1 allowlist (pdf/jpg/jpeg/png/webp/
--     md/markdown/txt). PAGE_IMAGE / ATTACHMENT belong to future
--     ingestion (BUSINESS-004 C1), clients never submit assetRole.
--   * original_name: display/audit ONLY (basename of the client
--     filename); never used as a physical path. original_relative_path:
--     fixed NULL this round (folder import is future) per
--     data-model.md §5.2.
--   * Index (space_id, source_id, created_at, id) fully covers the
--     list query `WHERE space_id = ? AND source_id = ? ORDER BY
--     created_at DESC, id DESC`.
--   * Charset utf8mb4 / utf8mb4_unicode_ci, matching all siblings.

CREATE TABLE source_asset (
    id                      BIGINT        NOT NULL AUTO_INCREMENT,
    space_id                BIGINT        NOT NULL,
    source_id               BIGINT        NOT NULL,
    asset_role              VARCHAR(32)   NOT NULL,
    original_name           VARCHAR(255)  NOT NULL,
    original_relative_path  VARCHAR(1024) NULL,
    storage_key             VARCHAR(512)  NOT NULL,
    mime_type               VARCHAR(127)  NOT NULL,
    size_bytes              BIGINT        NOT NULL,
    sha256                  CHAR(64)      NOT NULL,
    created_at              DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    KEY idx_source_asset_space_source_created (space_id, source_id, created_at, id),
    KEY idx_source_asset_space_sha256 (space_id, sha256),
    UNIQUE KEY uk_source_asset_storage_key (storage_key),

    CONSTRAINT fk_source_asset_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_source_asset_source
        FOREIGN KEY (source_id) REFERENCES source (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'SourceAsset: one RAW file object of a Source (BUSINESS-004). RAW bytes live in StorageService; this row is metadata + storageKey only. No ingestion/unzip/OCR in this slice.';
