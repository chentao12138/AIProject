-- V035__knowledge_point_lifecycle.sql
-- Final Backend Feature Freeze — KnowledgePoint lifecycle (§2.7).

ALTER TABLE knowledge_point
    ADD COLUMN archived_at DATETIME(6) NULL AFTER published_at,
    ADD COLUMN rejected_at DATETIME(6) NULL AFTER archived_at,
    ADD COLUMN rejected_reason TEXT NULL AFTER rejected_at;
