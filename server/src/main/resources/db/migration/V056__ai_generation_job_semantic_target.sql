-- V056__ai_generation_job_semantic_target.sql
--
-- AIGenerationJob: separate knowledgePointId from revisionId so
-- question-generation no longer reuses the revision column (C-30).
-- Also lease columns for atomic worker claim.

ALTER TABLE ai_generation_job
    ADD COLUMN knowledge_point_id BIGINT NULL AFTER revision_id,
    ADD COLUMN claimed_by VARCHAR(128) NULL AFTER requester,
    ADD COLUMN claimed_at DATETIME(6) NULL AFTER claimed_by;
