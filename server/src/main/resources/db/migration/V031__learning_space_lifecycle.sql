-- V031__learning_space_lifecycle.sql
-- Final Backend Feature Freeze — LearningSpace lifecycle columns (§2.1).

ALTER TABLE learning_space ADD COLUMN archived_at DATETIME(6) NULL AFTER updated_at;
