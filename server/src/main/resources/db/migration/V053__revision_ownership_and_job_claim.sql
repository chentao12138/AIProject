-- V053__revision_ownership_and_job_claim.sql
--
-- Final Backend: real extraction-revision ownership + DB-backed job claim.
--
-- source_page / content_block / source_outline_node now belong to an
-- extraction_revision. Reprocess creates a NEW revision and writes new
-- rows; old published revision data is retained for provenance.
--
-- ingestion_job gains a lease/claim column set so only one worker
-- processes a job at a time and crash recovery can re-queue stale
-- PROCESSING rows.

ALTER TABLE source
    ADD COLUMN current_extraction_revision_id BIGINT NULL AFTER status;

ALTER TABLE source_page
    ADD COLUMN extraction_revision_id BIGINT NULL AFTER outline_node_id;
ALTER TABLE source_page
    ADD INDEX idx_source_page_revision (space_id, extraction_revision_id);

ALTER TABLE content_block
    ADD COLUMN extraction_revision_id BIGINT NULL;
ALTER TABLE content_block
    ADD INDEX idx_content_block_revision (space_id, extraction_revision_id);

ALTER TABLE source_outline_node
    ADD COLUMN extraction_revision_id BIGINT NULL AFTER parent_id;

ALTER TABLE ingestion_job
    ADD COLUMN claimed_by VARCHAR(128) NULL AFTER created_by_user_id,
    ADD COLUMN claimed_at DATETIME(6) NULL AFTER claimed_by,
    ADD COLUMN last_stage_status VARCHAR(32) NULL AFTER stage;

ALTER TABLE ingestion_issue
    ADD COLUMN source_id BIGINT NULL AFTER space_id,
    ADD COLUMN extraction_revision_id BIGINT NULL AFTER source_page_id,
    ADD COLUMN safe_message TEXT NULL AFTER message;

-- Optional FK on source.current_extraction_revision_id is deferred until
-- all backfill paths exist; service enforces same-space ownership.
