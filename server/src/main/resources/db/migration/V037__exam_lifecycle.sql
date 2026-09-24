-- V037__exam_lifecycle.sql
-- Final Backend Feature Freeze — Exam lifecycle (§2.9).

ALTER TABLE exam ADD COLUMN archived_at DATETIME(6) NULL AFTER published_at;
