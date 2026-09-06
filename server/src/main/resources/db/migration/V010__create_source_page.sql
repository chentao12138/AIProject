-- V010__create_source_page.sql
--
-- BUSINESS-006 — production SourcePage table (EXTRACTED layer,
-- docs/data-model.md §6.1).
--
-- One SourcePage = one independently orderable/referenceable page of
-- a Source. V1 (TXT/Markdown ingestion) creates exactly ONE page per
-- ingested text asset (pageOrder=1, pageType=BODY, orderStatus=AUTO):
-- the whole document is its single page, and ContentBlocks anchor to
-- it. PDF/page-image flows that produce real multi-page documents
-- (R-INGEST-003) arrive in a later slice.
--
-- Design notes:
--   * space_id + source_id + source_asset_id: FK chain source_page ->
--     source_asset -> source -> learning_space. Deliberately NO
--     owner_subject column — ownership is derived through the parent
--     chain; every read is an owner-scoped JOIN (BUSINESS-002/004
--     pattern) and writes validate source+asset FIRST via the
--     existing owner-scoped services.
--   * FKs use default RESTRICT (no CASCADE), matching V004..V009.
--     Never relax with CASCADE or FOREIGN_KEY_CHECKS=0.
--   * source_page_number: PDF-internal page number, NULL for text
--     assets (not a PDF).
--   * page_order: final reading order; 1-based, unique per source via
--     the index prefix (no DB-level UNIQUE constraint so a future
--     drag-reorder can swap values transactionally — same rationale
--     as V005 source ordering).
--   * order_status: AUTO / NEEDS_REVIEW / CONFIRMED (data-model.md
--     §6.1). V1 text ingestion sets AUTO (system-assigned order, no
--     human confirmation; the review workflow is a later slice).
--     order_confidence stays NULL until confidence-producing
--     extraction exists.
--   * extracted_text: LONGTEXT (up to 4GB) — the full decoded text of
--     a page. V1 TXT/Markdown defines 1 asset = 1 page and the text
--     ingestion limit is aistudy.ingestion.text.max-document-bytes
--     (default 64MB), so a TEXT column (~64KB) would be a data
--     capacity lie; LONGTEXT covers the whole 64MB envelope
--     (AUTORUN-4H-PRE-RUNTIME-FIX-01). Individual ContentBlocks stay
--     bounded (TEXT, 60KB UTF-8 split) for provenance citation.
--     extraction_confidence stays NULL until confidence-producing
--     extraction exists.
--   * Indexes:
--       (space_id, source_id, page_order, id) — ordered page list
--       (space_id, source_asset_id)          — asset lookup
--   * Charset utf8mb4 / utf8mb4_unicode_ci, matching all siblings.

CREATE TABLE source_page (
    id                     BIGINT        NOT NULL AUTO_INCREMENT,
    space_id               BIGINT        NOT NULL,
    source_id              BIGINT        NOT NULL,
    source_asset_id        BIGINT        NULL,
    source_page_number     INT           NULL,
    page_order             INT           NOT NULL,
    printed_page_number    INT           NULL,
    page_type              VARCHAR(32)   NOT NULL,
    order_confidence       DOUBLE        NULL,
    order_status           VARCHAR(32)   NOT NULL,
    extracted_text         LONGTEXT      NULL,
    extraction_confidence  DOUBLE        NULL,
    created_at             DATETIME(6)   NOT NULL,
    updated_at             DATETIME(6)   NOT NULL,

    PRIMARY KEY (id),

    KEY idx_source_page_space_source_order (space_id, source_id, page_order, id),
    KEY idx_source_page_space_asset (space_id, source_asset_id),

    CONSTRAINT fk_source_page_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_source_page_source
        FOREIGN KEY (source_id) REFERENCES source (id),
    CONSTRAINT fk_source_page_asset
        FOREIGN KEY (source_asset_id) REFERENCES source_asset (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'SourcePage: one orderable/referenceable page of a Source (data-model.md 6.1). V1 TXT/MD ingestion creates one page per text asset (pageOrder=1, pageType=BODY, orderStatus=AUTO). EXTRACTED layer (BUSINESS-006).';
