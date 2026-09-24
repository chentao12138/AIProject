-- V042__create_ingestion_issue.sql
-- Final Backend Feature Freeze — IngestionIssue (pipeline quality tracking).

CREATE TABLE ingestion_issue (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    space_id        BIGINT       NOT NULL,
    ingestion_job_id BIGINT      NULL,
    source_page_id  BIGINT       NULL,
    issue_type      VARCHAR(64)  NOT NULL,
    severity        VARCHAR(32)  NOT NULL,
    message         TEXT         NOT NULL,
    status          VARCHAR(32)  NOT NULL DEFAULT 'OPEN',
    resolved_by     BIGINT       NULL,
    resolved_at     DATETIME(6)  NULL,
    created_at      DATETIME(6)  NOT NULL,

    PRIMARY KEY (id),

    KEY idx_issue_space_job (space_id, ingestion_job_id),
    KEY idx_issue_space_page (space_id, source_page_id),

    CONSTRAINT fk_issue_space FOREIGN KEY (space_id) REFERENCES learning_space (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'IngestionIssue: structured quality/risk issues from the ingestion pipeline.';
