-- V034__knowledge_category_lifecycle.sql
-- Final Backend Feature Freeze — KnowledgeCategory lifecycle (§2.6).

ALTER TABLE knowledge_category ADD COLUMN deleted_at DATETIME(6) NULL AFTER updated_at;
