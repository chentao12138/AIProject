-- V036__question_lifecycle.sql
-- Final Backend Feature Freeze — Question lifecycle (§2.8).

ALTER TABLE question
    ADD COLUMN archived_at DATETIME(6) NULL AFTER published_at,
    ADD COLUMN rejected_at DATETIME(6) NULL AFTER archived_at,
    ADD COLUMN rejected_reason TEXT NULL AFTER rejected_at;
