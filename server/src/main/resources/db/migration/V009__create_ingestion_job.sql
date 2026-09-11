-- V009__create_ingestion_job.sql
--
-- BUSINESS-005 — production IngestionJob table (ingestion process state).
--
-- One IngestionJob = one ingestion attempt for a Source (SourceDocument
-- metadata per data-model.md §7.1) and, in V1, exactly one SourceAsset
-- (the file being ingested: ZIP → ORIGINAL_PACKAGE, TXT/MD/PDF/image →
-- ORIGINAL_FILE). The job is process state, not business content — it
-- may be cleaned up later (data-model.md §7.1: "Job 不是业务资料本身，
-- 属于过程状态，可按需要清理历史").
--
-- Design notes:
--   * space_id + source_id + asset_id: FK chain ingestion_job ->
--     source_asset -> source -> learning_space (asset_id nullable: a
--     future job may not be tied to a single asset). Deliberately NO
--     owner_subject column — ownership is derived through the parent
--     chain; every read is an owner-scoped JOIN (BUSINESS-002/004
--     pattern) and create validates source + asset via the existing
--     SourceService.getMine / SourceAssetService.getMine FIRST.
--   * FKs use default RESTRICT (no CASCADE), matching V004..V008.
--     Never relax with CASCADE or FOREIGN_KEY_CHECKS=0.
--   * status = minimal V1 lifecycle: PENDING -> RUNNING -> SUCCEEDED /
--     FAILED (BACKEND_AUTORUN_4H.md §5.3). PARTIAL_FAILED (content-
--     ingestion.md §6) is deferred — V1 TXT/MD ingestion is
--     all-or-nothing per asset.
--   * stage = pipeline position from content-ingestion.md §6:
--     QUEUED / IMPORTING / EXTRACTING / STRUCTURING / AI_PROCESSING /
--     NEEDS_REVIEW / PUBLISHED. Service-enforced, no CHECK constraint
--     (project convention, cf. V005 source.status).
--   * progress_percent 0..100, service-enforced.
--   * error_code: stable machine-readable code (ZIP_SAFETY_VIOLATION,
--     future PARSE_FAILED etc.); error_message: SAFE diagnostic only —
--     no stack trace, no absolute paths (content-ingestion.md §6:
--     "不要把完整 stack trace 暴露给客户端"), truncated to 1000 chars
--     by the service.
--   * created_by_user_id: audit metadata (who started the job), NOT an
--     authorization key (cf. V005 source.created_by_user_id).
--   * Indexes:
--       (space_id, source_id, created_at, id) — source job history list
--       (space_id, status, created_at, id)   — active/pending lookup
--       (space_id, asset_id)                 — asset lookup
--   * Charset utf8mb4 / utf8mb4_unicode_ci, matching all siblings.

CREATE TABLE ingestion_job (
    id                 BIGINT        NOT NULL AUTO_INCREMENT,
    space_id           BIGINT        NOT NULL,
    source_id          BIGINT        NOT NULL,
    asset_id           BIGINT        NULL,
    status             VARCHAR(32)   NOT NULL,
    stage              VARCHAR(32)   NOT NULL,
    progress_percent   INT           NOT NULL DEFAULT 0,
    started_at         DATETIME(6)   NULL,
    finished_at        DATETIME(6)   NULL,
    retry_count        INT           NOT NULL DEFAULT 0,
    error_code         VARCHAR(64)   NULL,
    error_message      VARCHAR(1000) NULL,
    created_by_user_id VARCHAR(255)  NOT NULL,
    created_at         DATETIME(6)   NOT NULL,
    updated_at         DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    KEY idx_ingestion_job_space_source_created (space_id, source_id, created_at, id),
    KEY idx_ingestion_job_space_status_created (space_id, status, created_at, id),
    KEY idx_ingestion_job_space_asset (space_id, asset_id),

    CONSTRAINT fk_ingestion_job_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_ingestion_job_source
        FOREIGN KEY (source_id) REFERENCES source (id),
    CONSTRAINT fk_ingestion_job_asset
        FOREIGN KEY (asset_id) REFERENCES source_asset (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'IngestionJob: one ingestion attempt for a Source (+V1 asset). Process state only (data-model.md 7.1); PENDING->RUNNING->SUCCEEDED/FAILED; safe error_code/error_message, no stack traces (BUSINESS-005).';
