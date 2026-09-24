-- V045__create_note_source.sql
-- Final Backend Feature Freeze — NoteSource M:N.

CREATE TABLE note_source (
    id                BIGINT      NOT NULL AUTO_INCREMENT,
    note_id           BIGINT      NOT NULL,
    space_id          BIGINT      NOT NULL,
    content_block_id  BIGINT      NOT NULL,
    relation_type     VARCHAR(32) NULL,
    created_at        DATETIME(6) NOT NULL,

    PRIMARY KEY (id),

    UNIQUE KEY uk_note_source_pair (note_id, content_block_id),
    KEY idx_note_source_space (space_id),
    KEY idx_note_source_block (content_block_id),

    CONSTRAINT fk_note_source_note FOREIGN KEY (note_id) REFERENCES note (id),
    CONSTRAINT fk_note_source_space FOREIGN KEY (space_id) REFERENCES learning_space (id),
    CONSTRAINT fk_note_source_block FOREIGN KEY (content_block_id) REFERENCES content_block (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci
  COMMENT = 'NoteSource: M:N note<->content_block provenance.';
