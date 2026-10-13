-- V057__add_note_context_links.sql
--
-- Notes contract (C-01): a note is written against one primary context, so
-- the five reference columns live on the row itself and travel with every
-- NoteView. The M:N link tables from V044/V045 stay available for extra
-- links; nothing here replaces them.
--
-- ON DELETE SET NULL (not CASCADE): deleting an ingested block or a
-- discarded question must drop the note's pointer, never the note itself.
--
-- title becomes nullable: the contract allows an untitled note.

ALTER TABLE note
    ADD COLUMN source_id           BIGINT NULL AFTER content_format,
    ADD COLUMN source_page_id      BIGINT NULL AFTER source_id,
    ADD COLUMN content_block_id    BIGINT NULL AFTER source_page_id,
    ADD COLUMN knowledge_point_id  BIGINT NULL AFTER content_block_id,
    ADD COLUMN question_id         BIGINT NULL AFTER knowledge_point_id,
    MODIFY COLUMN title VARCHAR(255) NULL;

ALTER TABLE note
    ADD CONSTRAINT fk_note_source           FOREIGN KEY (source_id)          REFERENCES source (id)          ON DELETE SET NULL,
    ADD CONSTRAINT fk_note_source_page      FOREIGN KEY (source_page_id)     REFERENCES source_page (id)     ON DELETE SET NULL,
    ADD CONSTRAINT fk_note_content_block    FOREIGN KEY (content_block_id)   REFERENCES content_block (id)   ON DELETE SET NULL,
    ADD CONSTRAINT fk_note_knowledge_point  FOREIGN KEY (knowledge_point_id) REFERENCES knowledge_point (id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_note_question         FOREIGN KEY (question_id)        REFERENCES question (id)        ON DELETE SET NULL;

CREATE INDEX idx_note_space_user_updated
    ON note (space_id, user_subject_id, archived_at, updated_at);
