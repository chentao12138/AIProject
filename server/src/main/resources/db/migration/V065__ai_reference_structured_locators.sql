-- V065__ai_reference_structured_locators.sql
--
-- AI tutor contract (C-03 / C-04): a citation must be routable by the client,
-- not just readable. `entity_id` says which entity a reference names but not
-- which level of the source tree it sits at, so page reloads could not rebuild
-- /spaces/{spaceId}/sources/{sourceId}?page={sourcePageId}&block={contentBlockId}.
-- The three pointer columns make the level explicit; a CONTENT_BLOCK reference
-- carries its owning page AND source so the client never has to guess.
--
-- ON DELETE SET NULL (never CASCADE): deleting an ingested block or a discarded
-- knowledge point must drop the citation's pointer, never the assistant message
-- the learner received.
--
-- reference_type gains the two values the pipeline now writes: NOTE (search has
-- returned notes since V057, and grounded note citations used to violate the old
-- check) and SOURCE_PAGE (a selected source page; pages are not full-text
-- searchable, so SearchEntityType has no member for them).

ALTER TABLE ai_message_reference
    ADD COLUMN source_id        BIGINT NULL AFTER entity_id,
    ADD COLUMN source_page_id   BIGINT NULL AFTER source_id,
    ADD COLUMN content_block_id BIGINT NULL AFTER source_page_id;

ALTER TABLE ai_message_reference
    ADD CONSTRAINT fk_ai_message_reference_source        FOREIGN KEY (source_id)        REFERENCES source (id)        ON DELETE SET NULL,
    ADD CONSTRAINT fk_ai_message_reference_source_page   FOREIGN KEY (source_page_id)   REFERENCES source_page (id)   ON DELETE SET NULL,
    ADD CONSTRAINT fk_ai_message_reference_content_block FOREIGN KEY (content_block_id) REFERENCES content_block (id) ON DELETE SET NULL;

ALTER TABLE ai_message_reference
    DROP CHECK chk_ai_message_reference_type;

ALTER TABLE ai_message_reference
    ADD CONSTRAINT chk_ai_message_reference_type CHECK (
        reference_type IN ('SOURCE','SOURCE_PAGE','CONTENT_BLOCK','KNOWLEDGE_POINT','QUESTION','WRONG_QUESTION','NOTE')
    );
