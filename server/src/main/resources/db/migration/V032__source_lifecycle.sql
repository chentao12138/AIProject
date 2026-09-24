-- V032__source_lifecycle.sql
-- Final Backend Feature Freeze — Source lifecycle columns (§2.3, §3.14).

ALTER TABLE source
    ADD COLUMN archived_at DATETIME(6) NULL AFTER updated_at,
    ADD COLUMN review_status VARCHAR(32) NULL AFTER status,
    ADD COLUMN reviewed_at DATETIME(6) NULL AFTER review_status,
    ADD COLUMN reviewed_by VARCHAR(128) NULL AFTER reviewed_at,
    ADD COLUMN rejected_reason TEXT NULL AFTER reviewed_by;
