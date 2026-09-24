-- V040__extraction_revision.sql
-- Final Backend Feature Freeze — ExtractionRevision for source versioning (§3.15).

CREATE TABLE extraction_revision (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    space_id          BIGINT       NOT NULL,
    source_id         BIGINT       NOT NULL,
    version           INT          NOT NULL,
    status            VARCHAR(32)  NOT NULL DEFAULT 'DRAFT',
    extractor_version VARCHAR(64)  NULL,
    ocr_engine        VARCHAR(64)  NULL,
    ocr_model         VARCHAR(64)  NULL,
    created_at        DATETIME(6)  NOT NULL,
    published_at      DATETIME(6)  NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uk_extraction_revision_source_version (source_id, version),
    KEY idx_extraction_revision_space (space_id),

    CONSTRAINT fk_extraction_revision_space
        FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_extraction_revision_source
        FOREIGN KEY (source_id) REFERENCES source (id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'ExtractionRevision: versioned extraction result; reprocess creates new revision without destroying old published data.';
