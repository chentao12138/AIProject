-- V005__create_source.sql
--
-- BUSINESS-002 — production Source (SourceDocument metadata) table.
--
-- A Source is a registered learning material inside a LearningSpace
-- (docs/data-model.md §5.1 "SourceDocument": 逻辑资料，例如《数据库系统
-- 工程师教程》). This vertical slice registers Source METADATA only:
-- no binary upload, no file parsing, no ingestion pipeline, no AI.
--
-- Design notes:
--   * Table name `source` (task BUSINESS-002 B2: 创建正式 source)。
--   * Ownership model: a Source belongs to a LearningSpace; the
--     LearningSpace owns its owner_subject. There is deliberately NO
--     owner_subject column on source — ownership is derived through
--     the FK chain source.space_id -> learning_space.id, and every
--     read is owner-scoped through a JOIN (or a parent ownership
--     check first). This avoids duplicating the owner identity and
--     prevents a source row from ever contradicting its parent space.
--   * FK: space_id REFERENCES learning_space(id) with default
--     RESTRICT (no ON DELETE CASCADE). No convention in the project
--     docs forbids FKs; V001-V004 are SPIKE/simple tables, and this
--     is the first production FK. RESTRICT protects against deleting
--     a space that still has sources (delete is not implemented this
--     round anyway).
--   * title: NOT NULL, matches data-model.md §5.1 field name (the
--     docs call the human-readable name `title`, not `name`).
--   * source_type: VARCHAR(32) NOT NULL. Values are the documented
--     set from data-model.md §5.1: DESKTOP_UPLOAD,
--     DESKTOP_FOLDER_IMPORT, ADMIN_UPLOAD, ADMIN_MANUAL. No invented
--     enum; only the documented values are valid in the API.
--   * status: VARCHAR(32) NOT NULL DEFAULT 'REGISTERED'. This round
--     only registers metadata — there is no processing pipeline, so
--     PROCESSING/FAILED states are deliberately NOT introduced.
--     'REGISTERED' states that the source is recorded but not yet
--     imported. IngestionJob statuses (IMPORTING/AI_PROCESSING/
--     FAILED, see content-ingestion.md) belong to a future slice.
--   * created_by_user_id: VARCHAR(128) NOT NULL, audit field from
--     data-model.md §5.1 (createdByUserId), filled from the JWT sub
--     at create time. It is audit metadata, NOT the authorization
--     key — authorization comes from the parent LearningSpace.
--   * File-metadata columns (original_filename, mime_type,
--     size_bytes, storage_key, sha256) are NOT on this table: the
--     docs place them on SourceAsset (data-model.md §5.2), and this
--     slice performs no upload, so exposing placeholder columns
--     would violate the "no unused placeholder" rule (BUSINESS-002
--     B6). They arrive with the upload slice.
--   * Index: (space_id, created_at, id) covers the list query
--     `WHERE space_id = ? ORDER BY created_at DESC, id DESC` fully.
--   * Charset utf8mb4 / utf8mb4_unicode_ci, matching V003/V004.

CREATE TABLE source (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    space_id            BIGINT       NOT NULL,
    title               VARCHAR(255) NOT NULL,
    source_type         VARCHAR(32)  NOT NULL,
    status              VARCHAR(32)  NOT NULL DEFAULT 'REGISTERED',
    created_by_user_id  VARCHAR(128) NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),

    KEY idx_source_space_created (space_id, created_at, id),

    CONSTRAINT fk_source_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Source (SourceDocument metadata): a registered learning material inside a LearningSpace; ownership derived via space_id -> learning_space. Metadata only in BUSINESS-002, no upload/ingest yet.';
